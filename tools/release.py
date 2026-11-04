#!/usr/bin/env python3
"""Assemble a distribution only from a complete, verified per-target result set."""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
from pathlib import Path

from project import ROOT, TEST_MINIMUMS, check_layout, load_project
from verify_artifacts import ArtifactError, inspect_directory, write_manifest


def require_passed(counts: dict | None, minimum: int) -> None:
    if not counts or any(type(counts.get(k)) is not int or counts[k] < 0 for k in ("tests", "failures", "errors", "skipped")):
        raise ArtifactError("missing or invalid test counts")
    if counts["tests"] < minimum or any(counts[k] for k in ("failures", "errors", "skipped")):
        raise ArtifactError(f"test qualification incomplete: {counts}")


def assemble(source: Path, output: Path, expected_commit: str | None, mysql_file: Path | None,
             require_clean: bool, require_mysql: bool) -> None:
    check_layout()
    version, targets = load_project()
    if source.is_symlink() or output.exists() or source.resolve() == ROOT:
        raise ArtifactError("use a dedicated input directory and a new output directory")
    expected_names = {f"StateLink-{version}-mc{t['minecraft']}.jar" for t in targets}
    paths = list(source.rglob("*.jar"))
    if len(paths) != len(expected_names) or {p.name for p in paths} != expected_names:
        raise ArtifactError("input must contain exactly one current JAR for every supported target")
    qualifications = []
    for target in targets:
        candidates = list(source.rglob(f"qualification-mc{target['minecraft']}.json"))
        if len(candidates) != 1 or candidates[0].is_symlink():
            raise ArtifactError("one qualification record is required per target")
        item = json.loads(candidates[0].read_text())
        if item.get("mod_version") != version or item.get("minecraft") != target["minecraft"]:
            raise ArtifactError("qualification version/target mismatch")
        if not re.fullmatch(r"[a-f0-9]{40}", item.get("source_commit", "")):
            raise ArtifactError("invalid source commit")
        if require_clean and item.get("source_dirty") is not False:
            raise ArtifactError("release qualification requires a clean Git source tree")
        if expected_commit and item["source_commit"] != expected_commit:
            raise ArtifactError("qualification is for a different source commit")
        if os.environ.get("GITHUB_RUN_ID") and item.get("verification_run_id") != os.environ["GITHUB_RUN_ID"]:
            raise ArtifactError("qualification is for a different CI run")
        expected_scope = "primary-unit" if target["primary"] else "configuration-only"
        if item.get("test_scope") != expected_scope:
            raise ArtifactError("wrong test coverage classification")
        require_passed(item["unit"], TEST_MINIMUMS[expected_scope])
        qualifications.append(item)
    if len({q["source_commit"] for q in qualifications}) != 1:
        raise ArtifactError("mixed source commits")
    primary = next(t["minecraft"] for t in targets if t["primary"])
    mysql = json.loads(mysql_file.read_text()) if mysql_file else next(q for q in qualifications if q["minecraft"] == primary)
    if require_mysql:
        require_passed(mysql.get("mysql"), TEST_MINIMUMS["mysql"])
        if mysql.get("minecraft") != primary:
            raise ArtifactError("MySQL qualification is for a different target")
        if mysql.get("source_commit") != qualifications[0]["source_commit"] or mysql.get("mod_version") != version:
            raise ArtifactError("MySQL qualification source mismatch")
        if require_clean and mysql.get("source_dirty") is not False:
            raise ArtifactError("MySQL qualification requires clean source")
        if os.environ.get("GITHUB_RUN_ID") and mysql.get("verification_run_id") != os.environ["GITHUB_RUN_ID"]:
            raise ArtifactError("MySQL qualification is for a different CI run")
    output.mkdir(parents=True)
    for path in paths:
        if path.is_symlink():
            raise ArtifactError("artifact symlinks are not accepted")
        shutil.copy2(path, output / path.name)
    artifacts = inspect_directory(output, targets, version, ROOT)
    for artifact in artifacts:
        q = next(q for q in qualifications if q["minecraft"] == artifact["minecraft"])
        if q["artifact"]["sha256"] != artifact["sha256"] or q["artifact"]["bytes"] != artifact["bytes"]:
            raise ArtifactError("qualified artifact content changed")
        artifact["unit_tests"] = q["unit"]
        artifact["test_scope"] = q["test_scope"]
    evidence = {"source_commit": qualifications[0]["source_commit"],
                "source_dirty": any(q["source_dirty"] for q in qualifications),
                "verification_run_id": os.environ.get("GITHUB_RUN_ID"),
                "verification_run_url": (f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
                    if os.environ.get("GITHUB_RUN_ID") and os.environ.get("GITHUB_REPOSITORY") else None),
                "mysql_tests": mysql.get("mysql"), "minecraft_runtime": "NOT_RUN"}
    write_manifest(output, version, artifacts, evidence)
    print(f"PASS: complete {version} distribution, {len(artifacts)} targets, output={output}")


def verify_bundle(directory: Path, expected_commit: str | None, require_mysql: bool) -> dict:
    version, targets = load_project()
    if directory.is_symlink() or any(p.is_symlink() for p in directory.iterdir()):
        raise ArtifactError("distribution symlinks are not accepted")
    manifest = json.loads((directory / "manifest.json").read_text())
    expected_files = {f"StateLink-{version}-mc{t['minecraft']}.jar" for t in targets} | {"manifest.json", "SHA256SUMS"}
    if {p.name for p in directory.iterdir()} != expected_files:
        raise ArtifactError("distribution contains missing or unrelated files")
    artifacts = inspect_directory(directory, targets, version, ROOT)
    if manifest.get("schema") != 1 or manifest.get("mod_version") != version:
        raise ArtifactError("invalid distribution manifest")
    if manifest.get("minecraft_runtime") != "NOT_RUN" or any(a.get("minecraft_runtime") != "NOT_RUN" for a in manifest.get("artifacts", [])):
        raise ArtifactError("this pipeline has no runtime attestation; it cannot claim a runtime PASS")
    if expected_commit and (manifest.get("source_commit") != expected_commit or manifest.get("source_dirty") is not False):
        raise ArtifactError("distribution source provenance mismatch")
    recorded = manifest.get("artifacts", [])
    if len(recorded) != len(artifacts) or {a["minecraft"] for a in recorded} != {a["minecraft"] for a in artifacts}:
        raise ArtifactError("manifest target set mismatch")
    for item in artifacts:
        record = next(a for a in recorded if a["minecraft"] == item["minecraft"])
        for key in ("filename", "bytes", "sha256", "java", "loader", "fabric_api", "mappings", "refmap", "main_version", "archive_checks"):
            if record.get(key) != item[key]:
                raise ArtifactError(f"manifest mismatch: {item['minecraft']} {key}")
        target = next(t for t in targets if t["minecraft"] == item["minecraft"])
        require_passed(record.get("unit_tests"), TEST_MINIMUMS["primary-unit" if target["primary"] else "configuration-only"])
    sums = "".join(f"{a['sha256']}  {a['filename']}\n" for a in recorded)
    if (directory / "SHA256SUMS").read_text() != sums:
        raise ArtifactError("checksum record mismatch")
    if require_mysql:
        require_passed(manifest.get("mysql_tests"), TEST_MINIMUMS["mysql"])
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    collect = sub.add_parser("assemble")
    collect.add_argument("--input", type=Path, required=True)
    collect.add_argument("--output", type=Path, required=True)
    collect.add_argument("--mysql-qualification", type=Path)
    collect.add_argument("--require-clean", action="store_true")
    verify = sub.add_parser("verify")
    verify.add_argument("--input", type=Path, required=True)
    for command in (collect, verify):
        command.add_argument("--source-commit")
        command.add_argument("--require-mysql", action="store_true")
    args = parser.parse_args()
    if args.command == "assemble":
        assemble(args.input, args.output, args.source_commit, args.mysql_qualification, args.require_clean, args.require_mysql)
    else:
        verify_bundle(args.input, args.source_commit, args.require_mysql)
        print("PASS: distribution archive, target set, licenses, checksums and manifest")


if __name__ == "__main__":
    main()
