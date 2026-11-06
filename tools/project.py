"""Supported-target inventory shared by local tools and GitHub Actions."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from source_layout import check_sources, java_sources, metadata_text, mixin_source
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION_PATTERN = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
TEST_MINIMUMS = {"primary-unit": 208, "configuration-only": 25, "mysql": 109}


def check_legacy_fixtures(directory: Path) -> None:
    manifest = json.loads((directory / "manifest.json").read_text())
    artifacts = manifest["artifacts"]
    expected = {f"PlayerDataConnector-{v}.jar" for v in ("2.1.11", "2.2.6", "2.2.9")}
    if manifest.get("schema") != 1 or len(artifacts) != 3 or {a["filename"] for a in artifacts} != expected:
        raise ValueError("legacy migration fixture set mismatch")
    for item in artifacts:
        path = directory / item["filename"]
        if path.is_symlink() or path.stat().st_size != item["bytes"]:
            raise ValueError("legacy migration fixture size/path mismatch")
        if hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
            raise ValueError("legacy migration fixture SHA-256 mismatch")
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() or json.loads(archive.read("fabric.mod.json"))["version"] != item["version"]:
                raise ValueError("legacy migration fixture CRC/version mismatch")


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
        for key in ('gradle', 'loader', 'loom'):
            if not re.fullmatch(r'[0-9]+\.[0-9]+(?:\.[0-9]+)?(?:-SNAPSHOT)?', target[key]):
                raise ValueError('invalid toolchain version')
        if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+\+[0-9.]+', target['fabric_api']):
            raise ValueError('invalid Fabric API coordinate')
        if target['mappings'] != 'mojang-official' and not re.fullmatch(r'[0-9.]+\+build\.[0-9]+', target['mappings']):
            raise ValueError('invalid mappings coordinate')
        target["primary"] = target["minecraft"] == inventory["regression_baseline"]
    return values[0], targets


def check_license_layout(root: Path = ROOT) -> None:
    license_file = root / 'LICENSE'
    gpl_reference = root / 'docs/GPL-3.0-LGPLv3-reference.txt'
    if license_file.is_symlink() or not license_file.is_file():
        raise ValueError('the root LICENSE file must be present and regular')
    if (root / 'COPYING').exists() or (root / 'COPYING').is_symlink():
        raise ValueError('move the LGPLv3 GPL reference out of the repository root')
    if gpl_reference.is_symlink() or not gpl_reference.is_file():
        raise ValueError('the LGPLv3 GPL reference text is missing')


def check_layout(root: Path = ROOT) -> None:
    check_legacy_fixtures(root / 'tests/fixtures/legacy')
    version, targets = load_project(root)
    check_license_layout(root)
    check_sources(root, targets)
    if any((root / t['minecraft'] / 'src').exists() for t in targets):
        raise ValueError('maintained per-version source trees must not coexist with shared sources')
    properties = dict(line.split('=', 1) for line in (root / 'gradle.properties').read_text().splitlines()
                      if '=' in line and not line.startswith('#'))
    baseline = next(t['minecraft'] for t in targets if t['primary'])
    if properties.get('minecraft_version') != baseline:
        raise ValueError('IDE/default target differs from the regression baseline')
    for path in ('settings.gradle', 'build.gradle', 'gradlew', 'gradlew.bat', 'gradle/common.gradle'):
        if not (root / path).is_file() or (root / path).is_symlink():
            raise ValueError('missing shared build entrypoint')
    wrapper_jar = root / 'gradle/wrapper/gradle-wrapper.jar'
    recorded = json.loads((root / 'gradle/wrapper/checksums.json').read_text())['wrapper_sha256']
    if hashlib.sha256(wrapper_jar.read_bytes()).hexdigest() != recorded:
        raise ValueError('Gradle wrapper bytecode checksum mismatch')
    for target in targets:
        entry = java_sources(root, target['minecraft'])['com/atsukigames/statelink/StateLink.java'].read_text()
        metadata = json.loads(metadata_text(root, target, version))
        wrapper = (root / f"gradle/wrapper/gradle-{target['gradle']}.properties").read_text()
        if f'VERSION = "{version}"' not in entry or metadata.get('license') != 'LGPL-3.0-only':
            raise ValueError('source version/license mismatch')
        if f"gradle-{target['gradle']}-bin.zip" not in wrapper or not re.search(r'^distributionSha256Sum=[a-f0-9]{64}$', wrapper, re.M):
            raise ValueError('Gradle distribution/checksum mismatch')
        if not set(target.get('wrapper_options', {})) <= {'networkTimeout', 'validateDistributionUrl'}:
            raise ValueError('unsupported wrapper override')


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
