"""Supported-target inventory shared by local tools and GitHub Actions."""
from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION_PATTERN = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
TEST_MINIMUMS = {"primary-unit": 208, "configuration-only": 25, "mysql": 109}


def load_project(root: Path = ROOT) -> tuple[str, list[dict]]:
    version_lines = (root / "version.properties").read_text().splitlines()
    values = [line.removeprefix("mod_version=") for line in version_lines if line.startswith("mod_version=")]
    if len(values) != 1 or not VERSION_PATTERN.fullmatch(values[0]):
        raise ValueError("version.properties must contain one semantic mod_version")
    inventory = json.loads((root / "minecraft-versions.json").read_text())
    targets = inventory["targets"]
    names = [target["minecraft"] for target in targets]
    if inventory.get("schema") != 1 or not names or len(set(names)) != len(names):
        raise ValueError("invalid or duplicate Minecraft target inventory")
    if inventory["regression_baseline"] not in names:
        raise ValueError("regression baseline is not a supported target")
    for target in targets:
        if not re.fullmatch(r"[0-9]+\.[0-9]+(?:\.[0-9]+)?", target["minecraft"]):
            raise ValueError("invalid Minecraft target name")
        if target["java"] not in (17, 21, 25) or not isinstance(target["refmap"], bool):
            raise ValueError("invalid Java/mapping contract")
        target["primary"] = target["minecraft"] == inventory["regression_baseline"]
    return values[0], targets


def check_layout(root: Path = ROOT) -> None:
    version, targets = load_project(root)
    expected = {target["minecraft"] for target in targets}
    actual = {p.name for p in root.iterdir() if p.is_dir() and (p / "build.gradle").is_file()}
    if actual != expected:
        raise ValueError(f"project set mismatch: missing={expected-actual}, extra={actual-expected}")
    for target in targets:
        directory = root / target["minecraft"]
        if directory.is_symlink():
            raise ValueError(f"project symlink is not supported: {directory.name}")
        gradle = (directory / "build.gradle").read_text()
        properties = (directory / "gradle.properties").read_text().splitlines()
        entry = (directory / "src/main/java/com/atsukigames/statelink/StateLink.java").read_text()
        wrapper = (directory / "gradle/wrapper/gradle-wrapper.properties").read_text()
        metadata = json.loads((directory / "src/main/resources/fabric.mod.json").read_text())
        contracts = [
            f"mod_version={version}" in properties,
            f'VERSION = "{version}"' in entry,
            f"'{version}'" in gradle,
            f"JavaLanguageVersion.of({target['java']})" in gradle,
            f"com.mojang:minecraft:{target['minecraft']}" in gradle,
            f"net.fabricmc:fabric-loader:{target['loader']}" in gradle,
            f"fabric-api:{target['fabric_api']}" in gradle,
            f"version '{target['loom']}'" in gradle,
            f"gradle-{target['gradle']}-bin.zip" in wrapper,
            "distributionSha256Sum=" in wrapper,
            metadata["depends"]["minecraft"].removeprefix("~") == target["minecraft"],
            metadata["depends"]["java"] == f">={target['java']}",
            metadata["license"] == "LGPL-3.0-only",
            "apply from: '../gradle/common.gradle'" in gradle,
        ]
        if target["mappings"] != "mojang-official":
            contracts.append(f"net.fabricmc:yarn:{target['mappings']}:v2" in gradle)
        if not all(contracts):
            raise ValueError(f"source/build/manifest contract mismatch: {target['minecraft']}")


def provenance(root: Path = ROOT) -> dict:
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).strip())
    return {"source_commit": commit, "source_dirty": dirty}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "matrix", "version", "baseline", "baseline-java", "max-java"))
    args = parser.parse_args()
    check_layout()
    version, targets = load_project()
    if args.command == "matrix":
        print(json.dumps({"include": [{"minecraft": t["minecraft"], "java": t["java"]} for t in targets]}))
    elif args.command == "version":
        print(version)
    elif args.command == "baseline":
        print(next(t["minecraft"] for t in targets if t["primary"]))
    elif args.command == "baseline-java":
        print(next(t["java"] for t in targets if t["primary"]))
    elif args.command == "max-java":
        print(max(t["java"] for t in targets))
    else:
        print(f"PASS: {len(targets)} supported projects; version {version}")


if __name__ == "__main__":
    main()
