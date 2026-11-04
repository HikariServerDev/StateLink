#!/usr/bin/env python3
"""Dispatch one unchanged Gradle wrapper/toolchain per target; no source-tree duplication."""
from __future__ import annotations
import os
import subprocess
import sys
from pathlib import Path
from project import ROOT, check_layout, load_project
from build import java_home
from source_layout import write_changed


def split_target_property(arguments: list[str]) -> tuple[list[str], list[str]]:
    selected, forwarded = [], []
    index = 0
    while index < len(arguments):
        argument = arguments[index]
        if argument in ('-P', '--project-prop'):
            if index + 1 == len(arguments):
                raise ValueError('missing Gradle project property')
            value = arguments[index + 1]
            index += 2
            original = [argument, value]
        elif argument.startswith('-P'):
            value = argument[2:]
            original = [argument]
            index += 1
        elif argument.startswith('--project-prop='):
            value = argument.split('=', 1)[1]
            original = [argument]
            index += 1
        else:
            forwarded.append(argument)
            index += 1
            continue
        if value.startswith('minecraft_version='):
            selected.append(value.split('=', 1)[1])
        else:
            forwarded.extend(original)
    return selected, forwarded


def selected_target(arguments: list[str], targets: list[dict]) -> dict:
    matches, _ = split_target_property(arguments)
    if len(matches) > 1:
        raise ValueError('specify minecraft_version at most once')
    mc = matches[0] if matches else next(t['minecraft'] for t in targets if t['primary'])
    target = next((t for t in targets if t['minecraft'] == mc), None)
    if target is None:
        raise ValueError('unsupported Minecraft target')
    return target


def main() -> None:
    check_layout()
    _, targets = load_project()
    args = sys.argv[1:]
    # Project/settings overrides would bypass cache and source-selection isolation.
    if any((not a.startswith('--') and a.startswith(('-p', '-b', '-c'))) or a in ('-p','--project-dir','-c','--settings-file','-b','--build-file','--project-cache-dir') or
           a.startswith(('--project-dir=','--settings-file=','--build-file=','--project-cache-dir=')) for a in args):
        raise ValueError('use the repository target selector, not an alternate Gradle project')
    target = selected_target(args, targets)
    home = java_home(target['java'])
    # A target may retain wrapper options that differ within the same Gradle family.
    # Separate generated launchers prevent concurrent builds from rewriting those options.
    wrapper = ROOT / '.gradle/statelink/wrappers' / target['minecraft']
    if any(p.is_symlink() for p in (wrapper, *wrapper.parents)):
        raise ValueError('wrapper workspace symlink')
    write_changed(wrapper / 'gradle-wrapper.jar', (ROOT / 'gradle/wrapper/gradle-wrapper.jar').read_bytes())
    text = (ROOT / f"gradle/wrapper/gradle-{target['gradle']}.properties").read_text()
    text += ''.join(f'{key}={value}\n' for key,value in target.get('wrapper_options',{}).items())
    write_changed(wrapper / 'gradle-wrapper.properties', text.encode())
    environment = os.environ.copy()
    environment['JAVA_HOME'] = str(home)
    executable = home / ('bin/java.exe' if os.name == 'nt' else 'bin/java')
    command = [str(executable), '-Xmx64m', '-Xms64m', '-XX:ActiveProcessorCount=2', '-classpath',
               str(wrapper / 'gradle-wrapper.jar'), 'org.gradle.wrapper.GradleWrapperMain',
               '--project-cache-dir', str(ROOT / '.gradle/statelink/project-cache' / target['minecraft']),
               f"-Pminecraft_version={target['minecraft']}"]
    command += split_target_property(args)[1]
    raise SystemExit(subprocess.run(command, cwd=ROOT, env=environment).returncode)

if __name__ == '__main__': main()
