#!/usr/bin/env python3
"""Negative publication gates. These fixtures are never publishable native assets."""
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from verify_native_bundle import validate


class NativeBundleGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def fixture(self):
        source = next((path for path in (Path('/lib64/libc.so.6'), Path('/lib/x86_64-linux-gnu/libc.so.6'))
                       if path.is_file()), None)
        if source is None:
            self.skipTest('Negative ELF fixture requires the Linux system C library')
        target = self.directory / 'libelasticpp.so'
        shutil.copyfile(source, target)
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        # Deliberately names the system C library as elasticpp to prove that
        # checksums and an ELF header cannot qualify a native runtime package.
        manifest = {'format': 'eslib-native-bundle-v1', 'elasticppCommit': 'a' * 40,
                    'cpuIsa': 'x86-64-v3',
                    'targetTriple': 'x86_64-unknown-linux-gnu', 'luceneVersion': '10.4.0',
                    'minimumGlibc': '99.0', 'sharedObjectSha256': digest,
                    'files': {'libelasticpp.so': digest}}
        self.write(manifest)
        return manifest

    def write(self, manifest):
        (self.directory / 'native-manifest.json').write_text(json.dumps(manifest))

    def test_no_native_manifest_cannot_be_published(self):
        with self.assertRaisesRegex(ValueError, 'Missing'):
            validate(self.directory)

    def test_real_elf_without_native_abi_cannot_be_published(self):
        self.fixture()
        with self.assertRaisesRegex(ValueError, 'Missing native ABI symbols'):
            validate(self.directory)

    def test_changed_binary_cannot_be_published(self):
        self.fixture()
        with (self.directory / 'libelasticpp.so').open('ab') as stream:
            stream.write(b'changed')
        with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
            validate(self.directory)

    def test_extra_dependency_cannot_be_published(self):
        self.fixture()
        (self.directory / 'unexpected.so').write_bytes(b'not native')
        with self.assertRaisesRegex(ValueError, 'immutable file set'):
            validate(self.directory)

    def test_duplicate_manifest_identity_cannot_be_published(self):
        (self.directory / 'native-manifest.json').write_text('{"format":"first","format":"second"}')
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            validate(self.directory)

    def test_unsafe_native_basename_cannot_be_published(self):
        manifest = self.fixture()
        digest = manifest['files'].pop('libelasticpp.so')
        manifest['files']['../libelasticpp.so'] = digest
        self.write(manifest)
        with self.assertRaisesRegex(ValueError, 'Incomplete native file set'):
            validate(self.directory)


if __name__ == '__main__':
    unittest.main()
