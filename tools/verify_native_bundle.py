#!/usr/bin/env python3
"""Read-only validation of the native files used by the platform publication."""
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys

REQUIRED_EXPORTS = (
    'getNativeEsCapabilitiesV1', 'freeNativeEsCapabilitiesV1', 'createShardV3',
    'updateShardWriteMappingV1', 'bulkShardDocumentsU64V1', 'commitShardV1',
    'removeShardV2', 'freeShardWriteResultV1', 'freeShardBulkWriteResultV1',
    'sealShardV1', 'probeArchivedShardV1', 'openArchivedShardV1',
    'queryArchivedShardV1', 'closeArchivedShardV1', 'freePaimonEsStatusV1',
    'freePaimonEsSealResultV1', 'freePaimonEsProbeResultV1',
    'freePaimonEsOpenArchivedShardResultV1', 'freePaimonEsArchivedQueryResultV1')
SYSTEM_LIBRARIES = {
    'libc.so.6', 'libm.so.6', 'libdl.so.2', 'libpthread.so.0', 'librt.so.1',
    'libgcc_s.so.1', 'libstdc++.so.6', 'ld-linux-x86-64.so.2', 'libresolv.so.2'}


def unique_object(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise ValueError('Duplicate manifest key')
        obj[key] = value
    return obj


def validate(directory):
    directory = Path(directory)
    manifest_path = directory / 'native-manifest.json'
    if not manifest_path.is_file() or manifest_path.is_symlink() or manifest_path.stat().st_size > 1048576:
        raise ValueError('Missing or oversized native manifest')
    with manifest_path.open() as stream:
        manifest = json.load(stream, object_pairs_hook=unique_object)
    if manifest.get('format') != 'eslib-native-bundle-v1':
        raise ValueError('Unsupported native manifest format')
    if not re.fullmatch('[0-9a-f]{40}', manifest.get('elasticppCommit', '')):
        raise ValueError('Full immutable elasticpp commit is required')
    if manifest.get('targetTriple') != 'x86_64-unknown-linux-gnu':
        raise ValueError('Only Linux x86_64 is declared by this package')
    if manifest.get('luceneVersion') != '10.4.0':
        raise ValueError('This native ABI baseline is Lucene 10.4.0')
    if manifest.get('cpuIsa') != 'x86-64-v3':
        raise ValueError('The first native runtime requires its declared x86-64-v3 build contract')
    if not re.fullmatch('[0-9]+\.[0-9]+', manifest.get('minimumGlibc', '')):
        raise ValueError('Missing glibc baseline')
    files = manifest.get('files')
    if not isinstance(files, dict) or not files or len(files) > 64 or 'libelasticpp.so' not in files:
        raise ValueError('Incomplete native file set')
    available = set(files)
    actual = {p.name for p in directory.iterdir() if '.so' in p.name}
    if actual != available:
        raise ValueError('Native files differ from the declared immutable file set')
    for name, expected in files.items():
        if not re.fullmatch('[A-Za-z0-9_-]+\.so(?:\.[0-9]+)*', name):
            raise ValueError('Invalid native basename')
        if not isinstance(expected, str) or not re.fullmatch('[0-9a-f]{64}', expected):
            raise ValueError('Invalid native SHA-256')
        path = directory / name
        if not path.is_file() or path.is_symlink():
            raise ValueError('Native files must be regular files')
        digest = hashlib.sha256()
        with path.open('rb') as stream:
            header = stream.read(64)
            digest.update(header)
            for data in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(data)
        if digest.hexdigest() != expected:
            raise ValueError('Native file SHA-256 mismatch: ' + name)
        if len(header) != 64 or header[:6] != b'\x7fELF\x02\x01' or struct.unpack('<H', header[18:20])[0] != 62:
            raise ValueError('Native file is not ELF64 x86_64: ' + name)
        dynamic = subprocess.check_output(['readelf', '-d', str(path)], universal_newlines=True)
        needed = set(re.findall(r'\(NEEDED\).*?\[([^\]]+)\]', dynamic))
        missing = needed - available - SYSTEM_LIBRARIES
        if missing:
            raise ValueError('Unpackaged DT_NEEDED dependencies: ' + ', '.join(sorted(missing)))
        if needed & available and '$ORIGIN' not in dynamic:
            raise ValueError('Bundled dependencies require an ORIGIN runtime search path')
        for search_path in re.findall(r'\((?:RPATH|RUNPATH)\).*?\[([^\]]*)\]', dynamic):
            if any(entry not in ('$ORIGIN', '${ORIGIN}') for entry in search_path.split(':')):
                raise ValueError('Native runtime paths must be limited to ORIGIN')
        versions = subprocess.check_output(['readelf', '--version-info', str(path)], universal_newlines=True)
        required_glibc = [tuple(map(int, version.split('.'))) for version in
                          re.findall(r'Name: GLIBC_([0-9]+\.[0-9]+(?:\.[0-9]+)?)\b', versions)]
        baseline = tuple(map(int, manifest['minimumGlibc'].split('.'))) + (0,)
        if any((version + (0,) * (3 - len(version))) > baseline for version in required_glibc):
            raise ValueError('ELF requires a newer glibc than its manifest: ' + name)
    if manifest.get('sharedObjectSha256') != files['libelasticpp.so']:
        raise ValueError('Library identity differs from the file manifest')
    exported = subprocess.check_output(['nm', '-D', '--defined-only', str(directory / 'libelasticpp.so')],
                                       universal_newlines=True)
    symbols = {line.split()[-1].split('@')[0] for line in exported.splitlines() if line.split()}
    missing = set(REQUIRED_EXPORTS) - symbols
    if missing:
        raise ValueError('Missing native ABI symbols: ' + ', '.join(sorted(missing)))
    return manifest


if __name__ == '__main__':
    try:
        if len(sys.argv) != 2:
            raise ValueError('Usage: verify_native_bundle.py <native-bundle-directory>')
        result = validate(sys.argv[1])
        print('NATIVE_BUNDLE_FILES_PASS commit=' + result['elasticppCommit'] +
              ' files=' + str(len(result['files'])))
        print('File validation is not a substitute for the real nativeIntegrationTest.')
    except (ValueError, OSError, subprocess.SubprocessError, json.JSONDecodeError) as failure:
        print('NATIVE_BUNDLE_FILES_FAIL: ' + str(failure), file=sys.stderr)
        sys.exit(1)
