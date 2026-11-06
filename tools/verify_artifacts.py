"""Strict ZIP/metadata/target-set checks. This is not a JVM or Mixin runtime verifier."""
from __future__ import annotations

import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import zipfile
from pathlib import Path, PurePosixPath

from source_layout import java_sources, mixin_source, metadata_text
from project import ROOT

MAX_JAR_BYTES = 32 * 1024 * 1024
MAX_EXPANDED_BYTES = 64 * 1024 * 1024
EXPECTED_LIBRARIES = {"HikariCP-5.0.1.jar", "mysql-connector-j-8.0.33.jar", "slf4j-api-1.7.36.jar"}
OWN_PACKAGE = "com/atsukigames/statelink/"
LICENSE_RESOURCES = {
    "LICENSE": "LICENSE", "COPYING": "docs/GPL-3.0-LGPLv3-reference.txt",
    "THIRD-PARTY-NOTICES.md": "THIRD-PARTY-NOTICES.md",
    "META-INF/licenses/HikariCP-5.0.1.txt": "licenses/HikariCP-5.0.1.txt",
    "META-INF/licenses/slf4j-api-1.7.36.txt": "licenses/slf4j-api-1.7.36.txt",
}
FORBIDDEN_PARTS = {"src", "test", "tests", "harness", "rehearsal", "staging-results", "world", "worlds", "logs", "crash-reports", ".git", ".gradle"}


class ArtifactError(ValueError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_version_constant(path: Path) -> str:
    """javap reads the ConstantValue field without initializing Minecraft/StateLink classes."""
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin/javap")
    if shutil.which("javap"):
        candidates.append(Path(shutil.which("javap")))
    candidates.append(Path.home() / ".gradle/jdks/temurin-25/bin/javap")
    for executable in dict.fromkeys(candidates):
        if not executable.is_file():
            continue
        result = subprocess.run([str(executable), "-J-Xmx128m", "-constants", "-classpath", str(path),
                                 "com.atsukigames.statelink.StateLink"], capture_output=True, text=True, timeout=20)
        if result.returncode:
            continue  # An older JDK may not understand a newer target's classfile.
        values = re.findall(r'\bVERSION\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)";', result.stdout)
        if len(values) != 1:
            raise ArtifactError("main VERSION constant missing or ambiguous")
        return values[0]
    raise ArtifactError("cannot inspect main VERSION constant; a compatible javap (25 for all targets) is required")


def _checked_zip(archive: zipfile.ZipFile) -> set[str]:
    entries = archive.infolist()
    names = [entry.filename for entry in entries]
    if len(entries) > 10000 or len(set(names)) != len(names):
        raise ArtifactError("too many or duplicate ZIP entries")
    if sum(entry.file_size for entry in entries) > MAX_EXPANDED_BYTES:
        raise ArtifactError("ZIP expansion budget exceeded")
    for entry in entries:
        name = entry.filename
        if "\\" in name or "\x00" in entry.orig_filename or ":" in name or name.startswith("/") or ".." in PurePosixPath(name).parts or entry.flag_bits & 1:
            raise ArtifactError("unsafe or encrypted ZIP entry")
        if (entry.external_attr >> 16) & 0o170000 == 0o120000:
            raise ArtifactError("ZIP symlink entry")
    bad = archive.testzip()
    if bad:
        raise ArtifactError(f"ZIP CRC failed: {bad}")
    return set(names)


def inspect_jar(path: Path, target: dict, version: str, root: Path = ROOT) -> dict:
    expected_name = f"StateLink-{version}-mc{target['minecraft']}.jar"
    if path.is_symlink() or path.name != expected_name or path.stat().st_size > MAX_JAR_BYTES:
        raise ArtifactError(f"unexpected artifact path/name/size: {path.name}")
    try:
        with zipfile.ZipFile(path) as archive:
            names = _checked_zip(archive)
            for name in names:
                parts = set(PurePosixPath(name).parts)
                if parts & FORBIDDEN_PARTS:
                    raise ArtifactError(f"development/private content in JAR: {name}")
                # Java packages named config are application code, not local config JSON.
                if "config" in parts and not (name.startswith(OWN_PACKAGE) and (name.endswith(".class") or name.endswith("/"))):
                    raise ArtifactError(f"local configuration in JAR: {name}")
                if re.search(r"(?:Test|Tests)(?:\$.*)?\.class$", name) or name.endswith((".log", ".hprof", ".jfr", ".java")) or name.startswith(("org/junit/", "org/mockito/")):
                    raise ArtifactError(f"test/development content in JAR: {name}")
                if Path(name).name in {"server.properties", "eula.txt", "usercache.json", ".env", "fabric-server-launcher.properties"}:
                    raise ArtifactError(f"local server configuration in JAR: {name}")
            for filename, source_file in LICENSE_RESOURCES.items():
                if archive.read(filename) != (root / source_file).read_bytes():
                    raise ArtifactError(f"missing or inconsistent license: {filename}")
            metadata = json.loads(archive.read("fabric.mod.json"))
            expected = {
                "id": "statelink", "name": "StateLink", "version": version,
                "environment": "server", "license": "LGPL-3.0-only",
                "schemaVersion": 1, "icon": "assets/statelink/icon.png",
            }
            if any(metadata.get(key) != value for key, value in expected.items()):
                raise ArtifactError("Mod identity/version/license mismatch")
            dependencies = metadata["depends"]
            source_metadata = json.loads(metadata_text(root, target, version))
            if dependencies["minecraft"].removeprefix("~") != target["minecraft"]:
                raise ArtifactError("wrong Minecraft target")
            if dependencies["java"] != f">={target['java']}" or dependencies["fabricloader"] != f">={target['loader']}":
                raise ArtifactError("wrong Java/Loader requirement")
            if dependencies.get("fabric-api") != source_metadata["depends"].get("fabric-api"):
                raise ArtifactError("wrong Fabric API requirement")
            if metadata["entrypoints"]["main"] != ["com.atsukigames.statelink.StateLink"]:
                raise ArtifactError("unexpected Mod entrypoint")
            if metadata["mixins"] != ["statelink.mixins.json"]:
                raise ArtifactError("unexpected Mixin configuration list")
            mixins = json.loads(archive.read("statelink.mixins.json"))
            source_mixins = json.loads(mixin_source(root, target["minecraft"]).read_text())
            if {k: v for k, v in mixins.items() if k != "refmap"} != {k: v for k, v in source_mixins.items() if k != "refmap"}:
                raise ArtifactError("packaged/source Mixin configuration mismatch")
            if mixins.get("required") is not True or mixins.get("compatibilityLevel") != f"JAVA_{target['java']}":
                raise ArtifactError("Mixin failure/Java contract weakened")
            classes = mixins.get("server", []) + mixins.get("mixins", []) + mixins.get("client", [])
            if not classes or len(set(classes)) != len(classes):
                raise ArtifactError("missing/duplicate Mixin classes")
            for mixin in classes:
                filename = mixins["package"].replace(".", "/") + "/" + mixin + ".class"
                if filename not in names:
                    raise ArtifactError(f"missing Mixin class: {mixin}")
            refmap = mixins.get("refmap")
            if bool(refmap) != target["refmap"]:
                raise ArtifactError("mapping/refmap mode mismatch")
            if refmap:
                mappings = json.loads(archive.read(refmap))
                if not isinstance(mappings, dict) or not mappings.get("mappings"):
                    raise ArtifactError("empty/invalid refmap")
            libraries = [item["file"] for item in metadata.get("jars", [])]
            if len(libraries) != 3 or {Path(name).name for name in libraries} != EXPECTED_LIBRARIES:
                raise ArtifactError("unexpected nested runtime libraries")
            if {name for name in names if name.endswith(".jar")} != set(libraries):
                raise ArtifactError("undeclared nested JAR")
            allowed_resources = {*LICENSE_RESOURCES, "META-INF/MANIFEST.MF", "fabric.mod.json",
                                 "statelink.mixins.json", "assets/statelink/icon.png", *libraries}
            if refmap:
                allowed_resources.add(refmap)
            if "assets/statelink/icon.png" not in names:
                raise ArtifactError("Mod icon missing")
            for name in names:
                if name.endswith("/") or name in allowed_resources or (name.startswith(OWN_PACKAGE) and name.endswith(".class")):
                    continue
                raise ArtifactError(f"undeclared resource in JAR: {name}")
            for library in libraries:
                with zipfile.ZipFile(io.BytesIO(archive.read(library))) as nested:
                    _checked_zip(nested)
                    if library.endswith("mysql-connector-j-8.0.33.jar") and not nested.read("LICENSE"):
                        raise ArtifactError("MySQL vendor license missing")
            major = target["java"] + 44
            own_classes = [name for name in names if name.startswith(OWN_PACKAGE) and name.endswith(".class")]
            production_sources = java_sources(root, target["minecraft"])
            if OWN_PACKAGE + "StateLink.class" not in own_classes:
                raise ArtifactError("missing main class")
            for name in own_classes:
                outer = name.split("$")[0].removesuffix(".class") + ".java"
                if outer not in production_sources:
                    raise ArtifactError(f"class has no production source: {name}")
                header = archive.read(name)[:8]
                if len(header) != 8 or header[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(header[6:8], "big") != major:
                    raise ArtifactError(f"invalid class header/Java target: {name}")
            if read_version_constant(path) != version:
                raise ArtifactError("main VERSION constant differs from Mod metadata")
    except (zipfile.BadZipFile, KeyError, UnicodeError, json.JSONDecodeError, TypeError, RuntimeError, NotImplementedError) as error:
        raise ArtifactError(f"invalid JAR {path.name}: {error}") from error
    return {"minecraft": target["minecraft"], "java": target["java"], "filename": path.name,
            "bytes": path.stat().st_size, "sha256": sha256(path), "loader": target["loader"],
            "fabric_api": target["fabric_api"], "mappings": target["mappings"],
            "refmap": bool(refmap), "main_version": version, "archive_checks": "PASS", "minecraft_runtime": "NOT_RUN"}


def inspect_directory(directory: Path, targets: list[dict], version: str, root: Path = ROOT) -> list[dict]:
    expected = {f"StateLink-{version}-mc{target['minecraft']}.jar" for target in targets}
    actual = {path.name for path in directory.glob("*.jar")}
    if directory.is_symlink() or actual != expected:
        raise ArtifactError(f"artifact set mismatch: missing={sorted(expected-actual)}, extra={sorted(actual-expected)}")
    return [inspect_jar(directory / f"StateLink-{version}-mc{target['minecraft']}.jar", target, version, root) for target in targets]


def write_manifest(directory: Path, version: str, artifacts: list[dict], evidence: dict) -> None:
    manifest = {"schema": 1, "mod_version": version, **evidence, "artifacts": artifacts}
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (directory / "SHA256SUMS").write_text("".join(f"{item['sha256']}  {item['filename']}\n" for item in artifacts))
