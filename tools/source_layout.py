"""Select exact class-level compatibility variants; never replace a port with another port."""
from __future__ import annotations
import json
import os
import tempfile
from pathlib import Path, PurePosixPath
from string import Template


def layout(root: Path) -> dict:
    data = json.loads((root / 'source-layout.json').read_text())
    if data.get('schema') != 1 or data.get('common_java') != 'src/main/java':
        raise ValueError('unsupported source layout')
    return data


def checked_path(root: Path, value: str) -> Path:
    relative = PurePosixPath(value)
    if relative.is_absolute() or '..' in relative.parts or '\\' in value or ':' in value:
        raise ValueError('unsafe source path')
    path = root / value
    if any(p.is_symlink() for p in (path, *path.parents) if p != root.parent):
        raise ValueError('source symlinks are not supported')
    if not path.is_file():
        raise ValueError(f'missing source: {value}')
    return path


def java_sources(root: Path, minecraft: str) -> dict[str, Path]:
    data = layout(root)
    known = {name for item in data['variants'] + data['mixins'] for name in item['targets']}
    if minecraft not in known:
        raise ValueError('unsupported source target')
    selected = {p.relative_to(root / 'src/main/java').as_posix(): p
                for p in (root / 'src/main/java').rglob('*.java')}
    for path in selected.values():
        checked_path(root, path.relative_to(root).as_posix())
    for item in data['variants']:
        if minecraft not in item['targets']:
            continue
        if item['path'] in selected:
            raise ValueError(f'duplicate Java source: {minecraft} {item["path"]}')
        if not item['source'].endswith('/java/' + item['path']):
            raise ValueError('source path does not match its package path')
        selected[item['path']] = checked_path(root, item['source'])
    if not selected:
        raise ValueError('empty Java source selection')
    return selected


def mixin_source(root: Path, minecraft: str) -> Path:
    matches = [item for item in layout(root)['mixins'] if minecraft in item['targets']]
    if len(matches) != 1:
        raise ValueError('exactly one Mixin configuration is required per target')
    return checked_path(root, matches[0]['source'])


def metadata_text(root: Path, target: dict, version: str = '${version}') -> str:
    return Template((root / 'src/main/resources/fabric.mod.json').read_text()).substitute(
        version=version, **target['metadata'])


def check_sources(root: Path, targets: list[dict]) -> None:
    data = layout(root)
    names = {t['minecraft'] for t in targets}
    declared = set()
    for item in data['variants'] + data['mixins']:
        path = checked_path(root, item['source'])
        members = item['targets']
        if not members or len(members) != len(set(members)) or not set(members) <= names:
            raise ValueError('unknown/duplicate target in source selection')
        if path in declared or not item['source'].startswith('src/compat/'):
            raise ValueError('duplicate or unscoped compatibility source')
        declared.add(path)
    actual = {p for p in (root / 'src/compat').rglob('*') if p.is_file()}
    if actual != declared:
        raise ValueError('undeclared or missing compatibility files')
    for target in targets:
        java_sources(root, target['minecraft'])
        mixins = json.loads(mixin_source(root, target['minecraft']).read_text())
        if not mixins.get('required') or mixins.get('compatibilityLevel') != f"JAVA_{target['java']}":
            raise ValueError('Mixin/Java safety contract mismatch')
        metadata = json.loads(metadata_text(root, target))
        deps = metadata['depends']
        if deps['minecraft'].removeprefix('~') != target['minecraft'] or deps['java'] != f">={target['java']}" or deps['fabricloader'] != f">={target['loader']}":
            raise ValueError('target resource dependency mismatch')


def workspace(root: Path, minecraft: str) -> Path:
    return root / '.gradle/statelink/targets' / minecraft


def build_directory(root: Path, minecraft: str) -> Path:
    return root / 'build/targets' / minecraft


def write_changed(path: Path, data: bytes) -> None:
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('generated path is a symlink')
    if path.exists() and path.read_bytes() == data:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix='.statelink-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as output:
            output.write(data)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def prepare(root: Path, target: dict) -> Path:
    """Materialize only selected compat files in an owned, isolated generated workspace."""
    mc = target['minecraft']
    directory = workspace(root, mc)
    marker = directory / '.statelink-generated'
    if directory.exists() and (not marker.is_file() or marker.read_text() != str(root) + '\n'):
        raise ValueError('refusing to adopt an unowned generated workspace')
    if any(p.is_symlink() for p in (directory, *directory.parents)):
        raise ValueError('generated workspace symlink')
    directory.mkdir(parents=True, exist_ok=True)
    write_changed(marker, (str(root) + '\n').encode())
    projected = directory / 'sources/java'
    selected = java_sources(root, mc)
    expected = set()
    for relative, source in selected.items():
        if source.is_relative_to(root / 'src/main/java'):
            continue
        destination = projected / relative
        expected.add(destination)
        write_changed(destination, source.read_bytes())
    # This directory is owned generated source, never a run/world or maintained source tree.
    for stale in projected.rglob('*'):
        if stale.is_symlink():
            raise ValueError('generated source symlink')
        if stale.is_file() and stale not in expected:
            stale.unlink()
    plugin = 'net.fabricmc.fabric-loom' if target['mappings'] == 'mojang-official' else 'fabric-loom'
    bootstrap = ("// Generated by tools/source_layout.py; edit the shared root build.gradle.\n"
                 f"plugins {{ id '{plugin}' version '{target['loom']}'; id 'maven-publish' }}\n"
                 f"ext.statelinkRoot = file({json.dumps(str(root))})\n"
                 f"ext.statelinkMinecraft = '{mc}'\n"
                 "apply from: new File(statelinkRoot, 'build.gradle')\n")
    write_changed(directory / 'build.gradle', bootstrap.encode())
    return directory
