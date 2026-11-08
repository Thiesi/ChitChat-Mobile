"""Bounded capture evidence checks; no real Gradle, adb or emulator invocation."""
import io
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import call,patch

import theme_comparison as runner


class ComparisonEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp=self.enterContext(tempfile.TemporaryDirectory())
        self.root=Path(self.temp)
        self.source=self.root/'ccm-theme-presentation';self.source.mkdir()
        self.destination=self.root/'report';self.destination.mkdir()

    def image(self,name='theme-light-default-home.png',width=360,height=640):
        path=self.source/name
        path.write_bytes(b'\x89PNG\r\n\x1a\n'+b'\0'*8+struct.pack('>II',width,height))
        return path

    def test_partial_evidence_is_retained_but_never_claimed_complete(self):
        path=self.image()
        records,complete=runner.collect(self.root,self.destination,'after')
        self.assertFalse(complete)
        self.assertEqual((360,640),(records[0]['widthPx'],records[0]['heightPx']))
        self.assertEqual(path.read_bytes(),(self.destination/path.name).read_bytes())

    def test_wrong_image_size_cannot_be_labelled_360_by_640(self):
        self.image(width=320)
        with self.assertRaises(ValueError):runner.collect(self.root,self.destination,'after')

    def test_font_scale_and_resource_dimensions_are_checked(self):
        path=self.source/'theme-dark-font200-configuration.json'
        path.write_text(json.dumps(dict(fontScale=1,widthDp=360,heightDp=640)))
        with self.assertRaises(ValueError):runner.collect(self.root,self.destination,'after')

    def test_excess_or_unexpected_files_are_not_copied(self):
        files=[Path(str(n)) for n in range(81)]
        with patch.object(Path,'rglob',return_value=iter(files)):
            with self.assertRaises(ValueError):runner.collect(self.root,self.destination,'after')
        self.assertEqual([],list(self.destination.iterdir()))
        self.image(name='other-app.png')
        with self.assertRaises(ValueError):runner.collect(self.root,self.destination,'after')

    def test_later_baseline_keeps_its_real_pure_black_matrix(self):
        self.source=self.root/'ccm-theme-baseline';self.source.mkdir()
        for palette in ('light','dark','black'):
            for scale in ('default','font200'):
                prefix=f'baseline-{palette}-{scale}'
                for scene in runner.SCENES:self.image(prefix+'-'+scene+'.png')
                (self.source/(prefix+'-configuration.json')).write_text(json.dumps(dict(
                    widthDp=360,heightDp=640,fontScale=2 if scale=='font200' else 1)))
        records,complete=runner.collect(self.root,self.destination,'before',True)
        self.assertTrue(complete);self.assertEqual(72,len(records))

    def run_capture(self,status,complete,kind='after'):
        (self.destination/'provenance.json').write_text(json.dumps(dict(kind=kind,worktree=str(self.root),testClass='fixture')))
        previous=dict(size=dict(physical='320x640',override=None),density=dict(physical='160',override='240'))
        with patch.object(runner,'REPORT',self.destination),patch.object(runner.subprocess,'run') as command,\
            patch.object(runner,'collect',return_value=([],complete)) as collect,\
            patch.object(runner,'display_snapshot',return_value=previous),patch.object(runner,'set_display') as display:
            command.return_value=subprocess.CompletedProcess([],status)
            result=runner.run(kind)
            command.assert_called_once()
            collect.assert_called_once_with(self.root/'app/build/outputs/connected_android_test_additional_output',self.destination,kind,False)
            self.assertEqual([call(dict(size='360x640',density='160')),call(dict(size='reset')),call(dict(density='240'))],display.call_args_list)
        record=json.loads((self.destination/'provenance.json').read_text())
        self.assertEqual(status,record['originalCaptureStatus'])
        self.assertEqual(previous,record['viewportBefore']);self.assertTrue(record['viewportRestored'])
        return result

    def test_original_display_policy_must_be_known_before_any_mutation(self):
        outputs=['Physical size: 320x640\n','Physical density: 160\nOverride density: 240\n']
        with patch.object(runner.subprocess,'check_output',side_effect=outputs):
            saved=runner.display_snapshot()
        self.assertIsNone(saved['size']['override']);self.assertEqual('240',saved['density']['override'])
        with patch.object(runner.subprocess,'check_output',return_value='Physical size: 320x640\nOverride size: unknown\n'):
            with self.assertRaises(ValueError):runner.display_snapshot()

    def test_failed_restore_cannot_erase_original_failure_or_claim_success(self):
        for status in (7,0):
            (self.destination/'provenance.json').write_text(json.dumps(dict(kind='after',worktree=str(self.root),testClass='fixture')))
            with patch.object(runner,'REPORT',self.destination),patch.object(runner,'collect',return_value=([],True)),\
                patch.object(runner,'display_snapshot',return_value=dict(size=dict(physical='320x640',override=None))),\
                patch.object(runner,'set_display',side_effect=[None,OSError()]),\
                patch.object(runner.subprocess,'run',return_value=subprocess.CompletedProcess([],status)) as command:
                self.assertEqual(status or 1,runner.run('after'));command.assert_called_once()
            record=json.loads((self.destination/'provenance.json').read_text())
            self.assertEqual(status,record['originalCaptureStatus']);self.assertFalse(record['viewportRestored'])

    def test_collection_never_turns_a_failed_capture_invocation_green(self):
        self.assertEqual(7,self.run_capture(7,True))
        self.assertEqual(7,self.run_capture(7,False))
        self.assertEqual(137,self.run_capture(-9,True))

    def test_success_without_required_images_fails_only_the_capture_job(self):
        self.assertEqual(1,self.run_capture(0,False))
        self.assertEqual(0,self.run_capture(0,True))
        self.assertEqual(0,self.run_capture(0,True,'before'))


if __name__=='__main__':unittest.main()
