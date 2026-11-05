#!/usr/bin/env python3
"""Prepare a generated target workspace for Gradle/IDE; performs no database or server work."""
from pathlib import Path
import argparse
from project import ROOT, load_project
from source_layout import prepare, check_sources

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--minecraft', required=True)
    args = parser.parse_args()
    _, targets = load_project()
    target = next((t for t in targets if t['minecraft'] == args.minecraft), None)
    if target is None:
        parser.error('unsupported Minecraft target')
    check_sources(ROOT, targets)
    print(prepare(ROOT, target))

if __name__ == '__main__': main()
