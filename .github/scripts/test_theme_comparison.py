"""Bounded capture evidence checks; no real Gradle, adb or emulator invocation."""
import io
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch

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

    def run_capture(self,status,complete):
        (self.destination/'provenance.json').write_text(json.dumps(dict(kind='after',worktree=str(self.root),testClass='fixture')))
        with patch.object(runner,'REPORT',self.destination),patch.object(runner.subprocess,'run') as command,\
            patch.object(runner,'collect',return_value=([],complete)):
            command.return_value=subprocess.CompletedProcess([],status)
            result=runner.run('after')
            command.assert_called_once()
        record=json.loads((self.destination/'provenance.json').read_text())
        self.assertEqual(status,record['originalCaptureStatus'])
        return result

    def test_collection_never_turns_a_failed_capture_invocation_green(self):
        self.assertEqual(7,self.run_capture(7,True))
        self.assertEqual(7,self.run_capture(7,False))
        self.assertEqual(137,self.run_capture(-9,True))

    def test_success_without_required_images_fails_only_the_capture_job(self):
        self.assertEqual(1,self.run_capture(0,False))
        self.assertEqual(0,self.run_capture(0,True))


if __name__=='__main__':unittest.main()
