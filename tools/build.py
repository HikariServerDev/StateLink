#!/usr/bin/env python3
"""Bounded per-Minecraft build entrypoint used unchanged locally and in CI."""
from __future__ import annotations

import argparse
import concurrent.futures
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from project import ROOT, check_layout, load_project, provenance
from source_layout import build_directory
from verify_artifacts import inspect_jar


def java_home(major: int) -> Path:
    candidates = [os.environ.get(f"JAVA_{major}_HOME"), os.environ.get(f"JAVA_HOME_{major}_X64"),
                  os.environ.get("JAVA_HOME"), f"/usr/lib/jvm/java-{major}-openjdk-amd64",
                  str(Path.home() / f".gradle/jdks/temurin-{major}")]
    for candidate in filter(None, candidates):
        executable = Path(candidate) / ("bin/java.exe" if os.name == "nt" else "bin/java")
        if executable.is_file():
            result = subprocess.run([str(executable), "-version"], capture_output=True, text=True, timeout=10)
            match = re.search(r'version "([0-9]+)', result.stdout + result.stderr)
            if result.returncode == 0 and match and int(match[1]) == major:
                return Path(candidate)
    raise ValueError(f"Java {major} required; set JAVA_{major}_HOME")


def check_memory(jobs: int) -> None:
    meminfo = Path("/proc/meminfo")
    if meminfo.exists():
        available = next(int(line.split()[1]) * 1024 for line in meminfo.read_text().splitlines()
                         if line.startswith("MemAvailable:"))
        if available < (jobs * 1536 + 512) * 1024 * 1024:
            raise ValueError("not enough available RAM for the requested bounded build")


def mysql_environment(environment: dict) -> None:
    host = environment.get("STATELINK_TEST_MYSQL_HOST")
    port = environment.get("STATELINK_TEST_MYSQL_PORT", "")
    database = environment.get("STATELINK_TEST_MYSQL_DATABASE", "")
    if host not in ("localhost", "127.0.0.1") or not re.fullmatch(r"[0-9]{1,5}", port) or not 1024 <= int(port) <= 65535 or int(port) == 3306:
        raise ValueError("MySQL tests require an explicit loopback non-3306 test port")
    if not re.fullmatch(r"statelink_test_[A-Za-z0-9_]{1,50}", database):
        raise ValueError("MySQL database must use the disposable statelink_test_* namespace")


def junit_counts(directory: Path) -> dict:
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    reports = sorted(directory.glob("TEST-*.xml"))
    if not reports:
        raise ValueError(f"JUnit reports missing: {directory}")
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
    if totals["tests"] == 0 or any(totals[key] for key in ("failures", "errors", "skipped")):
        raise ValueError(f"test suite did not pass without skips: {totals}")
    return totals


def build_target(target: dict, version: str, args: argparse.Namespace, output: Path, source: dict) -> dict:
    check_memory(args.jobs)
    mc = target["minecraft"]
    directory = build_directory(ROOT, mc)
    environment = os.environ.copy()
    environment["JAVA_HOME"] = str(java_home(target["java"]))
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS"):
        environment.pop(key, None)
    needs_mysql = (args.mysql or args.mysql_only) and target["primary"]
    if needs_mysql:
        mysql_environment(environment)
    else:
        for key in list(environment):
            if key.startswith("STATELINK_TEST_MYSQL_"):
                environment.pop(key)
    tasks = ["mysqlTest"] if args.mysql_only else ["test"] + (["mysqlTest"] if needs_mysql else []) + ["build"]
    command = [str(ROOT / "gradlew"), f"-Pminecraft_version={mc}", "--no-daemon", "--max-workers=1", "--rerun-tasks",
               "-Dorg.gradle.jvmargs=-Xmx768m -XX:ActiveProcessorCount=2", "-Dorg.gradle.parallel=false"]
    if args.offline:
        command.append("--offline")
    command += tasks
    log = output / "logs" / f"{mc}.log"
    log.parent.mkdir(exist_ok=True)
    started = time.monotonic()
    print(f"START {mc}: Java {target['java']}, tasks={','.join(tasks)}", flush=True)
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=ROOT, env=environment, stdout=stream,
                                stderr=subprocess.STDOUT, timeout=1800)
    if result.returncode:
        raise ValueError(f"{mc}: Gradle failed; see {log}")
    qualification = {"schema": 1, "mod_version": version, "minecraft": mc, **source,
                     "verification_run_id": os.environ.get("GITHUB_RUN_ID"),
                     "test_scope": "primary-unit" if target["primary"] else "configuration-only",
                     "unit": None if args.mysql_only else junit_counts(directory / "test-results/test"),
                     "mysql": junit_counts(directory / "test-results/mysqlTest") if needs_mysql else None,
                     "seconds": round(time.monotonic() - started, 3)}
    reports = output / "junit" / mc
    reports.mkdir(parents=True)
    for task in tasks:
        if task not in ("test", "mysqlTest"):
            continue
        task_reports = reports / task
        shutil.copytree(directory / "test-results" / task, task_reports,
                        ignore=shutil.ignore_patterns("binary"))
    if provenance() != source:
        raise ValueError("Git HEAD/worktree status changed during build; evidence cannot be adopted")
    if args.mysql_only:
        filename = "mysql-qualification.json"
    else:
        artifact = directory / "libs" / f"StateLink-{version}-mc{mc}.jar"
        inspected = inspect_jar(artifact, target, version)
        qualification["artifact"] = inspected
        shutil.copy2(artifact, output / artifact.name)
        filename = f"qualification-mc{mc}.json"
    (output / filename).write_text(json.dumps(qualification, indent=2) + "\n")
    print(f"PASS {mc}: {qualification['seconds']}s", flush=True)
    return qualification


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", action="append", help="repeat to select targets; default: all supported")
    parser.add_argument("--jobs", type=int, choices=(1, 2), default=1)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--mysql", action="store_true", help="also run the primary real-MySQL suite")
    parser.add_argument("--mysql-only", action="store_true", help="run only the primary real-MySQL suite")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    check_layout()
    version, targets = load_project()
    primary = next(t["minecraft"] for t in targets if t["primary"])
    selected = set(args.minecraft or (t["minecraft"] for t in targets))
    if not selected <= {t["minecraft"] for t in targets}:
        parser.error("unsupported Minecraft target")
    if args.mysql_only and selected != {primary}:
        parser.error(f"--mysql-only requires --minecraft {primary}")
    if args.mysql and primary not in selected:
        parser.error("--mysql requires the primary target")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    output = (args.output or ROOT / ".verification" / stamp).resolve()
    output.mkdir(parents=True, exist_ok=False)
    if shutil.disk_usage(output).free < 2 * 1024**3:
        raise ValueError("at least 2 GiB free disk space is required")
    source = provenance()
    failures, results = [], []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(build_target, target, version, args, output, source): target["minecraft"]
                   for target in targets if target["minecraft"] in selected}
        for future in concurrent.futures.as_completed(futures):
            try:
                results.append(future.result())
            except (ValueError, OSError, subprocess.SubprocessError) as error:
                failures.append({"minecraft": futures[future], "error": str(error)})
                print(f"FAIL {futures[future]}: {error}", file=sys.stderr)
    (output / "build-results.json").write_text(json.dumps({"results": results, "failures": failures}, indent=2) + "\n")
    print(f"Results: {len(results)} passed, {len(failures)} failed; {output}")
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
