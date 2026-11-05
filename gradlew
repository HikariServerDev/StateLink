#!/bin/sh
# Select the preserved Gradle/Java toolchain from minecraft-versions.json.
set -eu
STATELINK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "${STATELINK_PYTHON:-python3}" "$STATELINK_ROOT/tools/gradle_runner.py" "$@"
