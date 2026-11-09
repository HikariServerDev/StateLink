"""Structural migration contracts: exact previous source semantics, not runtime claims."""
import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
import project
import source_layout as sources
import gradle_runner


class SharedSourcesTest(unittest.TestCase):
    def test_original_class_selection_is_preserved_for_all_thirty_targets(self):
        golden = json.loads((project.ROOT / 'tests/fixtures/migration/2.4.2-source-manifest.json').read_text())
        version, targets = project.load_project()
        self.assertEqual({t['minecraft'] for t in targets}, set(golden['targets']))
        for target in targets:
            mc = target['minecraft']
            selected = sources.java_sources(project.ROOT, mc)
            expected = golden['targets'][mc]['java']
            self.assertEqual(set(expected), set(selected), mc)
            # Byte equivalence applies to the 2.4.2 migration itself, before later fixes.
            if version == '2.4.2':
                actual = {path: hashlib.sha256(file.read_bytes()).hexdigest()
                          for path, file in selected.items()}
                self.assertEqual(expected, actual, mc)

    def test_every_original_resource_is_preserved_including_dependency_constraints(self):
        golden = json.loads((project.ROOT / 'tests/fixtures/migration/2.4.2-source-manifest.json').read_text())
        for target in project.load_project()[1]:
            payloads = {'fabric.mod.json': sources.metadata_text(project.ROOT, target).encode(),
                        'statelink.mixins.json': sources.mixin_source(project.ROOT, target['minecraft']).read_bytes(),
                        'assets/statelink/icon.png': (project.ROOT/'src/main/resources/assets/statelink/icon.png').read_bytes()}
            actual = {p:hashlib.sha256(v).hexdigest() for p,v in payloads.items()}
            self.assertEqual(golden['targets'][target['minecraft']]['resources'], actual)

    def test_unsupported_target_never_falls_back_to_common_sources(self):
        with self.assertRaises(ValueError):sources.java_sources(project.ROOT, '99.99')
        with self.assertRaises(ValueError):sources.mixin_source(project.ROOT, '99.99')

    def test_duplicate_class_selection_is_rejected(self):
        data = copy.deepcopy(sources.layout(project.ROOT))
        duplicate = copy.deepcopy(next(x for x in data['variants'] if '1.18.2' in x['targets']))
        data['variants'].append(duplicate)
        with mock.patch.object(sources, 'layout', return_value=data):
            with self.assertRaises(ValueError):sources.java_sources(project.ROOT, '1.18.2')

    def test_missing_variant_and_unsafe_source_paths_are_not_ignored(self):
        for path in ('../outside.java','/outside.java','src/compat/missing.java'):
            with self.subTest(path=path), self.assertRaises(ValueError):sources.checked_path(project.ROOT,path)

    def test_wrapper_target_selection_is_explicit_and_baseline_aligned(self):
        _, targets = project.load_project()
        self.assertEqual('1.18.2',gradle_runner.selected_target([],targets)['minecraft'])
        self.assertEqual('26.3',gradle_runner.selected_target(['-Pminecraft_version=26.3'],targets)['minecraft'])
        for args in (['-P', 'minecraft_version=26.3'], ['--project-prop=minecraft_version=26.3']):
            self.assertEqual('26.3', gradle_runner.selected_target(args, targets)['minecraft'])
        for args in (['-Pminecraft_version=unknown'],['-Pminecraft_version=1.18','-Pminecraft_version=26.3']):
            with self.assertRaises(ValueError):gradle_runner.selected_target(args,targets)

    def test_generated_workspaces_are_isolated_and_only_stale_generated_files_are_removed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            (root/'src/main/java').mkdir(parents=True)
            (root/'src/main/java/Common.java').write_text('class Common {}')
            records=[]
            for mc in ('1.18.2','26.3'):
                p=root/f'src/compat/mc-{mc}/java/Specific.java';p.parent.mkdir(parents=True);p.write_text('class Specific {} // '+mc)
                records.append({'source':p.relative_to(root).as_posix(),'path':'Specific.java','targets':[mc]})
            (root/'source-layout.json').write_text(json.dumps({'schema':1,'common_java':'src/main/java','variants':records,'mixins':[]}))
            first=sources.prepare(root,{'minecraft':'1.18.2','loom':'1.2.8','mappings':'yarn'})
            second=sources.prepare(root,{'minecraft':'26.3','loom':'1.18-SNAPSHOT','mappings':'mojang-official'})
            stale=first/'sources/java/Obsolete.java';stale.write_text('obsolete')
            sources.prepare(root,{'minecraft':'1.18.2','loom':'1.2.8','mappings':'yarn'})
            self.assertFalse(stale.exists())
            self.assertIn('1.18.2',(first/'sources/java/Specific.java').read_text())
            self.assertIn('26.3',(second/'sources/java/Specific.java').read_text())
            self.assertEqual('class Common {}',(root/'src/main/java/Common.java').read_text())
            marker=first/'.statelink-generated';marker.unlink()
            with self.assertRaises(ValueError):sources.prepare(root,{'minecraft':'1.18.2','loom':'1.2.8','mappings':'yarn'})


class LicenseLayoutTest(unittest.TestCase):
    def test_only_lgpl_is_a_root_license_candidate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'LICENSE').write_text('GNU LESSER GENERAL PUBLIC LICENSE\n')
            reference = root / 'docs/GPL-3.0-LGPLv3-reference.txt'
            reference.parent.mkdir(parents=True)
            reference.write_text('GNU GENERAL PUBLIC LICENSE\n')

            project.check_license_layout(root)

            (root / 'COPYING').write_text('GNU GENERAL PUBLIC LICENSE\n')
            with self.assertRaisesRegex(ValueError, 'out of the repository root'):
                project.check_license_layout(root)

    def test_missing_gpl_reference_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'LICENSE').write_text('GNU LESSER GENERAL PUBLIC LICENSE\n')
            with self.assertRaisesRegex(ValueError, 'reference text is missing'):
                project.check_license_layout(root)


class ArchiveEquivalenceTest(unittest.TestCase):
    @staticmethod
    def jar(entries):
        import io,zipfile
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w') as archive:
            for name,value in entries.items():archive.writestr(name,value)
        return data.getvalue()

    def test_class_bytes_and_entry_sets_cannot_be_ignored(self):
        from compare_artifacts import compare_archives
        before=self.jar({'A.class':b'old'})
        for after in (self.jar({'A.class':b'new'}),self.jar({'B.class':b'old'})):
            with self.assertRaises(ValueError):compare_archives(before,after)

    def test_json_format_and_library_order_are_reported_without_weakening_other_fields(self):
        from compare_artifacts import compare_archives
        data={'id':'statelink','jars':[{'file':'b'},{'file':'a'}]}
        before=self.jar({'fabric.mod.json':json.dumps(data)})
        data['jars'].reverse()
        changes=compare_archives(before,self.jar({'fabric.mod.json':json.dumps(data,indent=2)}))
        self.assertEqual('json-format-or-nested-library-order',changes[0]['kind'])
        data['id']='wrong'
        with self.assertRaises(ValueError):compare_archives(before,self.jar({'fabric.mod.json':json.dumps(data)}))

    def test_namespace_and_refmap_changes_are_failures(self):
        from compare_artifacts import compare_archives
        for name,before,after in [('META-INF/MANIFEST.MF','Fabric-Mapping-Namespace: official\n','Fabric-Mapping-Namespace: named\n'),
                                  ('StateLink-refmap.json','{"mappings":{"a":"b"}}','{"mappings":{"a":"c"}}')]:
            with self.subTest(name=name),self.assertRaises(ValueError):compare_archives(self.jar({name:before}),self.jar({name:after}))


if __name__ == '__main__':
    unittest.main()
