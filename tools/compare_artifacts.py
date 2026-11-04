#!/usr/bin/env python3
"""Compare migration JAR payloads, keeping bytecode strict and reporting packaging-only changes."""
from __future__ import annotations
import argparse
import io
import json
import zipfile
from pathlib import Path
from project import ROOT, load_project
from verify_artifacts import inspect_jar, sha256, _checked_zip


def manifest_fields(payload: bytes) -> dict:
    # JAR manifests fold long lines; do not confuse wrapping with a changed value.
    lines = payload.decode().replace('\r\n','\n').replace('\n ', '').splitlines()
    return dict(line.split(': ',1) for line in lines if ': ' in line)


def compare_archives(before: bytes, after: bytes, prefix: str = '') -> list[dict]:
    changes=[]
    with zipfile.ZipFile(io.BytesIO(before)) as a, zipfile.ZipFile(io.BytesIO(after)) as b:
        names_a={n for n in _checked_zip(a) if not n.endswith('/')}
        names_b={n for n in _checked_zip(b) if not n.endswith('/')}
        if names_a != names_b:
            raise ValueError(f'entry set changed: {prefix} missing={names_a-names_b}, extra={names_b-names_a}')
        for name in sorted(names_a):
            x,y=a.read(name),b.read(name)
            if x==y:continue
            label=prefix+name
            if name.endswith('.class'):
                raise ValueError(f'bytecode changed: {label}')
            if name.endswith('.jar'):
                nested=compare_archives(x,y,label+'!/')
                changes += nested or [{'entry':label,'kind':'nested-zip-layout-only'}]
            elif name=='fabric.mod.json':
                old,new=json.loads(x),json.loads(y)
                for value in (old,new):
                    if 'jars' in value:value['jars']=sorted(value['jars'],key=lambda j:j['file'])
                if old != new:raise ValueError(f'Mod metadata semantics changed: {label}')
                changes.append({'entry':label,'kind':'json-format-or-nested-library-order'})
            elif name.endswith('-refmap.json'):
                if json.loads(x)!=json.loads(y):raise ValueError(f'refmap changed: {label}')
                changes.append({'entry':label,'kind':'json-format-only'})
            elif name=='META-INF/MANIFEST.MF':
                old,new=manifest_fields(x),manifest_fields(y)
                diagnostic={k:{'before':old.get(k),'after':new.get(k)} for k in set(old)|set(new) if old.get(k)!=new.get(k)}
                if set(diagnostic)-{'Fabric-Loom-Version'}:
                    raise ValueError(f'manifest semantics changed: {label}: {diagnostic}')
                changes.append({'entry':label,'kind':'build-tool-manifest-only','differences':diagnostic})
            else:
                raise ValueError(f'resource payload changed: {label}')
    return changes


def main() -> None:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline',type=Path,required=True)
    parser.add_argument('--candidate',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();version,targets=load_project();results=[]
    for target in targets:
        name=f"StateLink-{version}-mc{target['minecraft']}.jar"
        before,after=args.baseline/name,args.candidate/name
        item={'minecraft':target['minecraft'],'filename':name}
        try:
            old,new=inspect_jar(before,target,version),inspect_jar(after,target,version)
            changes=compare_archives(before.read_bytes(),after.read_bytes())
            with zipfile.ZipFile(after) as archive:
                classes=sum(n.endswith('.class') and n.startswith('com/atsukigames/statelink/') for n in archive.namelist())
            item.update(status='PASS',baseline_sha256=old['sha256'],candidate_sha256=new['sha256'],
                        byte_identical=old['sha256']==new['sha256'],own_classes_byte_identical=classes,packaging_changes=changes)
        except (ValueError,OSError,zipfile.BadZipFile) as error:
            item.update(status='FAIL',error=str(error))
        results.append(item)
    summary={'version':version,'scope':'class bytes, resource payloads, metadata/refmaps and nested dependency payloads; NOT Minecraft runtime',
             'passed':sum(r['status']=='PASS' for r in results),'failed':sum(r['status']=='FAIL' for r in results),'targets':results}
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(summary,indent=2)+'\n')
    print(f"JAR payload comparison: {summary['passed']}/{len(targets)} passed; {summary['failed']} failed")
    if summary['failed']:raise SystemExit(1)

if __name__=='__main__':main()
