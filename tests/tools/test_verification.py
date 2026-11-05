"""Adversarial tests for the common release gate; no Minecraft, MySQL or network access."""
import copy
import hashlib
import io
import json
import struct
import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import build
import project
import publish_release
import release
import verify_artifacts
from verify_artifacts import ArtifactError, EXPECTED_LIBRARIES, LICENSE_RESOURCES, inspect_directory, inspect_jar, write_manifest


class JarFixture(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.target = {"minecraft": "1.18.2", "java": 17, "loader": "0.17.3", "fabric_api": "test-api",
                       "mappings": "test-yarn", "refmap": True, "primary": True, "metadata": {}}
        self.version = "2.4.2"
        version_reader = mock.patch.object(verify_artifacts, "read_version_constant", return_value=self.version)
        version_reader.start(); self.addCleanup(version_reader.stop)
        (self.root / "LICENSE").write_text("fixture LGPL license\n")
        (self.root / "COPYING").write_text("fixture GPL reference\n")
        resource = self.root / "src/main/resources"
        resource.mkdir(parents=True)
        self.mixins = {"required": True, "package": "com.atsukigames.statelink.mixin",
                       "compatibilityLevel": "JAVA_17", "server": ["ExampleMixin"]}
        (resource / "statelink.mixins.json").write_text(json.dumps(self.mixins))
        self.metadata = {"id": "statelink", "name": "StateLink", "version": self.version, "schemaVersion": 1,
                         "icon": "assets/statelink/icon.png",
                         "environment": "server", "license": "LGPL-3.0-only",
                         "depends": {"minecraft": "~1.18.2", "java": ">=17", "fabricloader": ">=0.17.3", "fabric-api": "*"},
                         "entrypoints": {"main": ["com.atsukigames.statelink.StateLink"]},
                         "mixins": ["statelink.mixins.json"],
                         "jars": [{"file": f"META-INF/jars/{name}"} for name in sorted(EXPECTED_LIBRARIES)]}
        (resource / "fabric.mod.json").write_text(json.dumps(self.metadata))
        (self.root / "source-layout.json").write_text(json.dumps({"schema": 1, "common_java": "src/main/java", "variants": [], "mixins": [{"source": "src/main/resources/statelink.mixins.json", "targets": ["1.18.2"]}]}))
        self.entries = {"LICENSE": (self.root / "LICENSE").read_bytes(), "COPYING": (self.root / "COPYING").read_bytes(),
                        "assets/statelink/icon.png": b"fixture-image",
                        "fabric.mod.json": json.dumps(self.metadata).encode(),
                        "statelink.mixins.json": json.dumps({**self.mixins, "refmap": "StateLink-refmap.json"}).encode(),
                        "StateLink-refmap.json": b'{"mappings":{"example":{"a":"b"}}}'}
        for entry, filename in LICENSE_RESOURCES.items():
            path = self.root / filename
            if not path.exists():
                path.parent.mkdir(parents=True, exist_ok=True); path.write_text("fixture license/notice\n")
            self.entries[entry] = path.read_bytes()
        # These are deliberately just header fixtures: archive inspection does not promise JVM verification.
        for name in ("StateLink", "mixin/ExampleMixin"):
            entry = "com/atsukigames/statelink/" + name
            self.entries[entry + ".class"] = b"\xca\xfe\xba\xbe\x00\x00\x00\x3d"
            source = self.root / "src/main/java" / (entry + ".java")
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text("// fixture source\n")
        for name in EXPECTED_LIBRARIES:
            content = io.BytesIO()
            with zipfile.ZipFile(content, "w") as nested:
                nested.writestr("META-INF/NOTICE", "fixture")
                nested.writestr("LICENSE", "fixture vendor license")
            self.entries["META-INF/jars/" + name] = content.getvalue()
        self.jar = self.root / f"StateLink-{self.version}-mc1.18.2.jar"

    def write_jar(self, entries=None):
        with zipfile.ZipFile(self.jar, "w", compression=zipfile.ZIP_STORED) as archive:
            for name, data in (entries or self.entries).items():
                archive.writestr(name, data)
        return self.jar

    def inspect(self):
        return inspect_jar(self.write_jar(), self.target, self.version, self.root)

    def test_valid_archive_records_not_run_instead_of_claiming_runtime_pass(self):
        self.assertEqual("NOT_RUN", self.inspect()["minecraft_runtime"])

    def test_stale_compiled_version_cannot_hide_behind_new_metadata(self):
        with mock.patch.object(verify_artifacts, "read_version_constant", return_value="2.4.1"):
            with self.assertRaises(ArtifactError): self.inspect()

    def test_wrong_metadata_fields_are_rejected(self):
        for key, wrong in (("id", "other"), ("version", "9.9.9"), ("license", "MIT"), ("environment", "*")):
            with self.subTest(key=key):
                metadata = {**self.metadata, key: wrong}
                self.entries["fabric.mod.json"] = json.dumps(metadata).encode()
                with self.assertRaises(ArtifactError): self.inspect()

    def test_wrong_minecraft_java_or_loader_is_rejected(self):
        for key, wrong in (("minecraft", "1.19"), ("java", ">=21"), ("fabricloader", ">=0.1.0")):
            with self.subTest(key=key):
                metadata = copy.deepcopy(self.metadata)
                metadata["depends"][key] = wrong
                self.entries["fabric.mod.json"] = json.dumps(metadata).encode()
                with self.assertRaises(ArtifactError): self.inspect()

    def test_missing_license_and_reference_text_are_rejected(self):
        for name in LICENSE_RESOURCES:
            with self.subTest(name=name):
                entries = dict(self.entries); entries.pop(name)
                self.write_jar(entries)
                with self.assertRaises(ArtifactError): inspect_jar(self.jar, self.target, self.version, self.root)

    def test_unsupported_and_old_filename_are_rejected(self):
        self.write_jar()
        for name in ("StateLink-2.4.1-mc1.18.2.jar", "StateLink-2.4.2-mc1.19.jar"):
            path = self.root / name; path.write_bytes(self.jar.read_bytes())
            with self.assertRaises(ArtifactError): inspect_jar(path, self.target, self.version, self.root)

    def test_missing_or_extra_target_cannot_be_hidden_by_a_count(self):
        self.write_jar()
        with self.assertRaises(ArtifactError): inspect_directory(self.root, [self.target, {**self.target, "minecraft": "1.19"}], self.version, self.root)
        extra = self.root / "unexpected.jar"; extra.write_bytes(self.jar.read_bytes())
        with self.assertRaises(ArtifactError): inspect_directory(self.root, [self.target], self.version, self.root)

    def test_path_traversal_and_private_content_are_rejected(self):
        for name in ("../outside", "/absolute", "C:/outside", "config/statelink.json", "server.properties", "world/playerdata/x.dat",
                     "com/atsukigames/statelink/harness/Probe.class", "com/atsukigames/statelink/utils/CodecTest$Fixture.class", "private.txt"):
            with self.subTest(name=name):
                entries = {**self.entries, name: b"fixture"}
                self.write_jar(entries)
                with self.assertRaises(ArtifactError): inspect_jar(self.jar, self.target, self.version, self.root)

    def test_duplicate_zip_entry_is_rejected(self):
        self.write_jar()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.jar, "a") as archive: archive.writestr("LICENSE", b"duplicate")
        with self.assertRaises(ArtifactError): inspect_jar(self.jar, self.target, self.version, self.root)

    def test_truncated_zip_is_rejected(self):
        self.write_jar(); self.jar.write_bytes(self.jar.read_bytes()[:-22])
        with self.assertRaises(ArtifactError): inspect_jar(self.jar, self.target, self.version, self.root)

    def test_bad_outer_crc_is_rejected(self):
        self.write_jar()
        with zipfile.ZipFile(self.jar) as archive: info = archive.getinfo("LICENSE")
        data = bytearray(self.jar.read_bytes())
        name_len, extra_len = struct.unpack_from("<HH", data, info.header_offset + 26)
        data[info.header_offset + 30 + name_len + extra_len] ^= 1
        self.jar.write_bytes(data)
        with self.assertRaises(ArtifactError): inspect_jar(self.jar, self.target, self.version, self.root)

    def test_broken_nested_zip_is_rejected(self):
        name = next(iter(EXPECTED_LIBRARIES))
        self.entries["META-INF/jars/" + name] = b"not a zip"
        with self.assertRaises(ArtifactError): self.inspect()

    def test_missing_mixin_and_wrong_refmap_are_rejected(self):
        entries = dict(self.entries); entries.pop("com/atsukigames/statelink/mixin/ExampleMixin.class")
        self.write_jar(entries)
        with self.assertRaises(ArtifactError): inspect_jar(self.jar, self.target, self.version, self.root)
        self.entries["StateLink-refmap.json"] = b'{"mappings":{}}'
        with self.assertRaises(ArtifactError): self.inspect()

    def test_wrong_class_java_major_and_undeclared_nested_jar_are_rejected(self):
        entries = dict(self.entries)
        entries["com/atsukigames/statelink/StateLink.class"] = b"\xca\xfe\xba\xbe\x00\x00\x00\x41"
        self.write_jar(entries)
        with self.assertRaises(ArtifactError): inspect_jar(self.jar, self.target, self.version, self.root)
        self.entries["META-INF/jars/extra.jar"] = b"extra"
        with self.assertRaises(ArtifactError): self.inspect()

    def test_symlink_artifact_is_rejected(self):
        self.write_jar(); actual = self.root / "actual.jar"; self.jar.rename(actual); self.jar.symlink_to(actual)
        with self.assertRaises(ArtifactError): inspect_jar(self.jar, self.target, self.version, self.root)

    def bundle(self):
        artifact = self.inspect()
        artifact["unit_tests"] = {"tests": 208, "failures": 0, "errors": 0, "skipped": 0}
        artifact["test_scope"] = "primary-unit"
        directory = self.root / "bundle"; directory.mkdir()
        (directory / self.jar.name).write_bytes(self.jar.read_bytes())
        write_manifest(directory, self.version, [artifact], {"source_commit": "a"*40, "source_dirty": False,
                       "mysql_tests": {"tests": 109, "failures": 0, "errors": 0, "skipped": 0}, "minecraft_runtime": "NOT_RUN"})
        return directory

    def verify_bundle(self, directory):
        with mock.patch.object(release, "load_project", return_value=(self.version, [self.target])), mock.patch.object(release, "ROOT", self.root):
            return release.verify_bundle(directory, "a"*40, True)

    def test_valid_bundle_and_its_source_identity_are_checked(self):
        self.assertEqual("a"*40, self.verify_bundle(self.bundle())["source_commit"])

    def test_manifest_and_checksum_tampering_are_rejected(self):
        directory = self.bundle()
        (directory / "SHA256SUMS").write_text("forged")
        with self.assertRaises(ArtifactError): self.verify_bundle(directory)

    def test_wrong_commit_and_invented_runtime_pass_are_rejected(self):
        directory = self.bundle(); path = directory / "manifest.json"
        original = json.loads(path.read_text())
        for changed in ({**original, "source_commit": "b"*40}, {**original, "source_dirty": True},
                        {**original, "minecraft_runtime": "PASS"}):
            path.write_text(json.dumps(changed))
            with self.assertRaises(ArtifactError): self.verify_bundle(directory)

    def test_extra_logs_and_symlink_manifests_are_not_distributions(self):
        directory = self.bundle(); log = directory / "build.log"; log.write_text("private log")
        with self.assertRaises(ArtifactError): self.verify_bundle(directory)
        log.unlink(); manifest = directory / "manifest.json"; actual = self.root / "manifest-real.json"
        manifest.rename(actual); manifest.symlink_to(actual)
        with self.assertRaises(ArtifactError): self.verify_bundle(directory)


class LegacyFixtureTest(unittest.TestCase):
    def test_immutable_inputs_reject_missing_corrupt_or_wrong_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            records = []
            for version in ("2.1.11", "2.2.6", "2.2.9"):
                path = directory / f"PlayerDataConnector-{version}.jar"
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("fabric.mod.json", json.dumps({"version": version}))
                records.append({"version": version, "filename": path.name, "bytes": path.stat().st_size,
                                "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
            manifest = {"schema": 1, "artifacts": records}
            (directory / "manifest.json").write_text(json.dumps(manifest))
            project.check_legacy_fixtures(directory)
            path = directory / records[0]["filename"]
            original = path.read_bytes()
            path.write_bytes(original[:-1])
            with self.assertRaises(ValueError): project.check_legacy_fixtures(directory)
            path.write_bytes(bytes([original[0] ^ 1]) + original[1:])
            with self.assertRaises(ValueError): project.check_legacy_fixtures(directory)
            path.write_bytes(original)
            records[0]["version"] = "9.9.9"
            (directory / "manifest.json").write_text(json.dumps(manifest))
            with self.assertRaises(ValueError): project.check_legacy_fixtures(directory)
            path.unlink()
            with self.assertRaises(FileNotFoundError): project.check_legacy_fixtures(directory)


class BuildSafetyTest(unittest.TestCase):
    def test_mysql_requires_disposable_loopback_namespace_and_nonproduction_port(self):
        valid = {"STATELINK_TEST_MYSQL_HOST": "127.0.0.1", "STATELINK_TEST_MYSQL_PORT": "33306", "STATELINK_TEST_MYSQL_DATABASE": "statelink_test_ci"}
        build.mysql_environment(valid)
        for key, value in (("STATELINK_TEST_MYSQL_HOST", "db.example"), ("STATELINK_TEST_MYSQL_PORT", "3306"),
                           ("STATELINK_TEST_MYSQL_DATABASE", "hikari"), ("STATELINK_TEST_MYSQL_PORT", "99999"),
                           ("STATELINK_TEST_MYSQL_PORT", "03306"), ("STATELINK_TEST_MYSQL_PORT", "+33306"),
                           ("STATELINK_TEST_MYSQL_PORT", "00033306"), ("STATELINK_TEST_MYSQL_PORT", "٣٣٣٠٦")):
            with self.subTest(key=key):
                with self.assertRaises(ValueError): build.mysql_environment({**valid, key: value})

    def test_junit_skips_failures_and_absence_are_not_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError): build.junit_counts(root)
            for attribute in ("skipped", "failures", "errors"):
                (root / "TEST-example.xml").write_text(f'<testsuite tests="1" {attribute}="1"/>')
                with self.assertRaises(ValueError): build.junit_counts(root)

    def test_bounded_memory_gate_rejects_unsafe_admission(self):
        with mock.patch.object(Path, "read_text", return_value="MemAvailable: 1000 kB\n"), mock.patch.object(Path, "exists", return_value=True):
            with self.assertRaises(ValueError): build.check_memory(2)

    def test_qualification_counts_cannot_be_skipped_or_decreased(self):
        release.require_passed({"tests": 208, "failures": 0, "errors": 0, "skipped": 0}, 208)
        for counts in ({"tests": 207, "failures": 0, "errors": 0, "skipped": 0},
                       {"tests": 208, "failures": 0, "errors": 0, "skipped": 1},
                       {"tests": 208, "failures": False, "errors": 0, "skipped": 0}):
            with self.assertRaises(ArtifactError): release.require_passed(counts, 208)


class PublicationGuardTest(unittest.TestCase):
    def setUp(self):
        self.run = {"status": "completed", "conclusion": "success", "event": "push", "head_branch": "main",
                    "path": ".github/workflows/verify.yml", "head_sha": "a"*40,
                    "repository": {"full_name": publish_release.REPOSITORY},
                    "head_repository": {"full_name": publish_release.REPOSITORY}}

    def test_trusted_main_success_resolves_an_exact_commit(self):
        self.assertEqual("a"*40, publish_release.validate_run(self.run, "v2.4.2"))

    def test_fork_pr_failed_or_different_workflow_runs_cannot_release(self):
        for changed in ({"event": "pull_request"}, {"head_branch": "feature"}, {"conclusion": "failure"},
                        {"status": "in_progress"}, {"path": ".github/workflows/other.yml"},
                        {"head_repository": {"full_name": "fork/StateLink"}}, {"head_repository": None}, {"head_sha": "not-a-commit"}):
            with self.subTest(changed=changed):
                with self.assertRaises(ValueError): publish_release.validate_run({**self.run, **changed}, "v2.4.2")

    def test_invalid_tag_is_not_forwarded_to_github(self):
        for tag in ("main", "v2.4.2 --force", "../../outside", "v2.4.2-beta"):
            with self.assertRaises(ValueError): publish_release.validate_run(self.run, tag)

    def publication_context(self):
        return (mock.patch.object(publish_release, "check_layout"),
                mock.patch.object(publish_release, "load_project", return_value=("2.4.2", [])),
                mock.patch.object(publish_release, "resolve", return_value="a"*40),
                mock.patch.object(publish_release, "provenance", return_value={"source_commit": "a"*40, "source_dirty": False}),
                mock.patch.object(publish_release, "verify_bundle", return_value={"verification_run_id": "1"}))

    def test_existing_tag_is_never_replaced(self):
        from contextlib import ExitStack
        with ExitStack() as stack:
            for patch in self.publication_context(): stack.enter_context(patch)
            api = stack.enter_context(mock.patch.object(publish_release, "gh_api", return_value={"object": {"sha": "a"*40}}))
            writer = stack.enter_context(mock.patch.object(publish_release, "run_gh"))
            with self.assertRaises(ValueError): publish_release.publish(1, "v2.4.2", Path("unused"))
            api.assert_called_once_with("git/ref/tags/v2.4.2", allow_missing=True)
            writer.assert_not_called()

    def test_existing_release_is_never_replaced(self):
        from contextlib import ExitStack
        with ExitStack() as stack:
            for patch in self.publication_context(): stack.enter_context(patch)
            api = stack.enter_context(mock.patch.object(publish_release, "gh_api", side_effect=[None, {"id": 1}]))
            writer = stack.enter_context(mock.patch.object(publish_release, "run_gh"))
            with self.assertRaises(ValueError): publish_release.publish(1, "v2.4.2", Path("unused"))
            self.assertEqual(2, api.call_count)
            writer.assert_not_called()

    def test_failed_download_verification_leaves_release_in_draft(self):
        from contextlib import ExitStack
        with tempfile.TemporaryDirectory() as temporary, ExitStack() as stack:
            for patch in self.publication_context(): stack.enter_context(patch)
            source = Path(temporary) / "source"; source.mkdir(); (source / "artifact.jar").write_bytes(b"expected")
            stack.enter_context(mock.patch.object(publish_release, "gh_api", side_effect=[None, None, {"ref": "created"}]))
            def github(*args):
                if args[:2] == ("release", "download"):
                    destination = Path(args[args.index("--dir") + 1]); (destination / "artifact.jar").write_bytes(b"corrupted")
            writer = stack.enter_context(mock.patch.object(publish_release, "run_gh", side_effect=github))
            with self.assertRaises(ValueError): publish_release.publish(1, "v2.4.2", source)
            self.assertFalse(any(call.args[:2] == ("release", "edit") for call in writer.call_args_list))


if __name__ == "__main__":
    unittest.main()
