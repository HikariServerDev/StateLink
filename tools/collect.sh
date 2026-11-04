#!/bin/bash
# Compatibility entrypoint: collection now requires qualified build output and strict verification.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 tools/release.py assemble "$@"
