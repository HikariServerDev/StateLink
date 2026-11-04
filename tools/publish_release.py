#!/usr/bin/env python3
"""Publish a new immutable release from a successful trusted-main verification run."""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import tempfile
from pathlib import Path

from project import check_layout, load_project, provenance
from release import verify_bundle
from verify_artifacts import sha256

REPOSITORY = "HikariServerDev/StateLink"


def gh_api(path: str, payload: dict | None = None, allow_missing: bool = False):
    command = ["gh", "api", f"repos/{REPOSITORY}/{path}"]
    if payload is not None:
        command += ["--method", "POST", "--input", "-"]
    result = subprocess.run(command, input=json.dumps(payload) if payload is not None else None,
                            capture_output=True, text=True, timeout=90)
    if result.returncode:
        if allow_missing and "(HTTP 404)" in result.stderr:
            return None
        raise RuntimeError(result.stderr.strip())
    return json.loads(result.stdout)


def validate_run(run: dict, tag: str) -> str:
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
        raise ValueError("tag must be vMAJOR.MINOR.PATCH")
    if run.get("status") != "completed" or run.get("conclusion") != "success":
        raise ValueError("verification run has not completed successfully")
    if run.get("event") not in {"push", "workflow_dispatch"} or run.get("head_branch") != "main":
        raise ValueError("only trusted main verification runs can release")
    if run.get("path", "").split("@")[0] != ".github/workflows/verify.yml":
        raise ValueError("wrong verification workflow")
    if any((run.get(key) or {}).get("full_name") != REPOSITORY for key in ("repository", "head_repository")):
        raise ValueError("fork/foreign verification run")
    commit = run.get("head_sha", "")
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("invalid verified source commit")
    return commit


def resolve(run_id: int, tag: str) -> str:
    if run_id <= 0:
        raise ValueError("positive verification run ID required")
    return validate_run(gh_api(f"actions/runs/{run_id}"), tag)


def run_gh(*args: str) -> None:
    subprocess.run(["gh", *args, "--repo", REPOSITORY], check=True, timeout=600)


def publish(run_id: int, tag: str, directory: Path) -> None:
    check_layout()
    version, _ = load_project()
    commit = resolve(run_id, tag)
    source = provenance()
    if tag != f"v{version}" or source != {"source_commit": commit, "source_dirty": False}:
        raise ValueError("tag, checked-out source and verified run must match exactly")
    manifest = verify_bundle(directory, commit, require_mysql=True)
    if manifest.get("verification_run_id") != str(run_id):
        raise ValueError("distribution belongs to a different verification run")
    if gh_api(f"git/ref/tags/{tag}", allow_missing=True) is not None:
        raise ValueError("tag already exists; never overwrite a historical tag")
    if gh_api(f"releases/tags/{tag}", allow_missing=True) is not None:
        raise ValueError("release already exists; never replace historical assets")
    assets = sorted(directory.iterdir())
    hashes = {path.name: sha256(path) for path in assets}
    gh_api("git/refs", {"ref": f"refs/tags/{tag}", "sha": commit})
    notes = (f"Source: `{commit}`. Verified by Actions run {run_id}.\n\n"
             "Per-Minecraft JARs, SHA256SUMS and manifest.json only. The manifest records exact test coverage; "
             "Minecraft runtime and production login acceptance are NOT claimed.\n\n"
             "License: LGPL-3.0-only; LICENSE and the referenced GPL text are included in each JAR. "
             "Source code is available at this tag. Recovery defaults remain last_checkpoint and accept "
             "rollback/World-state duplication or loss risk. Stop all writers and back up the DB before updating.")
    run_gh("release", "create", tag, "--verify-tag", "--target", commit, "--draft",
           "--title", f"StateLink {version}", "--notes", notes, *(str(path) for path in assets))
    # A release stays draft until the actual uploaded bytes are downloaded and checked.
    with tempfile.TemporaryDirectory(prefix="statelink-release-check-") as temporary:
        downloaded = Path(temporary)
        run_gh("release", "download", tag, "--dir", str(downloaded))
        if {p.name: sha256(p) for p in downloaded.iterdir()} != hashes:
            raise ValueError("uploaded assets differ; release left in draft for investigation")
        verify_bundle(downloaded, commit, require_mysql=True)
    if gh_api(f"git/ref/tags/{tag}")["object"]["sha"] != commit:
        raise ValueError("tag identity changed; release left in draft")
    run_gh("release", "edit", tag, "--draft=false")
    print(f"Published {tag} from {commit}; verification run {run_id}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("resolve", "publish"))
    parser.add_argument("--run-id", type=int, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--input", type=Path)
    args = parser.parse_args()
    if args.command == "resolve":
        print(f"source_commit={resolve(args.run_id, args.tag)}")
    elif args.input is None:
        parser.error("publish requires --input")
    else:
        publish(args.run_id, args.tag, args.input)


if __name__ == "__main__":
    main()
