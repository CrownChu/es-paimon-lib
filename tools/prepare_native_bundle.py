#!/usr/bin/env python3
"""Package existing native artifacts; derive provenance from the loaded library."""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import shutil
from verify_native_bundle import validate


class Status(ctypes.Structure):
    _fields_ = [('abiVersion', ctypes.c_uint32), ('structSize', ctypes.c_uint32),
               ('success', ctypes.c_int32), ('errorCode', ctypes.c_int32),
               ('retryable', ctypes.c_int32), ('mutationStarted', ctypes.c_int32),
               ('rebuildRequired', ctypes.c_int32), ('reserved0', ctypes.c_int32),
               ('diagnostic', ctypes.c_void_p), ('diagnosticLength', ctypes.c_int32),
               ('reserved1', ctypes.c_int32), ('reserved', ctypes.c_uint64 * 4)]


class Capabilities(ctypes.Structure):
    _fields_ = [('status', Status), ('minimumAbiVersion', ctypes.c_uint32),
               ('maximumAbiVersion', ctypes.c_uint32), ('capabilityFlags', ctypes.c_uint64),
               ('maximumVectorDimensions', ctypes.c_uint32), ('reserved0', ctypes.c_uint32),
               ('gitCommit', ctypes.c_void_p), ('gitCommitLength', ctypes.c_int32), ('reserved1', ctypes.c_int32),
               ('buildInfo', ctypes.c_void_p), ('buildInfoLength', ctypes.c_int32), ('reserved2', ctypes.c_int32),
               ('luceneVersion', ctypes.c_void_p), ('luceneVersionLength', ctypes.c_int32), ('reserved3', ctypes.c_int32),
               ('targetTriple', ctypes.c_void_p), ('targetTripleLength', ctypes.c_int32), ('reserved4', ctypes.c_int32),
               ('minimumGlibc', ctypes.c_void_p), ('minimumGlibcLength', ctypes.c_int32), ('reserved5', ctypes.c_int32),
               ('sharedObjectSha256', ctypes.c_ubyte * 32), ('reserved', ctypes.c_uint64 * 4)]


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(data)
    return value.hexdigest()


def bounded_string(value, name):
    pointer, length = getattr(value, name), getattr(value, name + 'Length')
    if not pointer or not 0 < length <= 128:
        raise ValueError('Invalid native identity string: ' + name)
    return ctypes.string_at(pointer, length).decode('utf-8', errors='strict')


def require_v3_cpu():
    required = set('cx16 lahf_lm popcnt pni ssse3 sse4_1 sse4_2 avx avx2 bmi1 bmi2 f16c fma movbe xsave'.split())
    with Path('/proc/cpuinfo').open() as stream:
        data = stream.read(8 * 1024 * 1024 + 1)
    if len(data) > 8 * 1024 * 1024:
        raise ValueError('CPU metadata exceeds its limit')
    seen = False
    for line in data.splitlines():
        name, separator, flags = line.partition(':')
        if separator and name.strip() == 'flags':
            seen = True
            flags = set(flags.split())
            if not required <= flags or not flags.intersection({'abm', 'lzcnt'}):
                raise ValueError('Bundle preparation requires an x86-64-v3 CPU')
    if not seen:
        raise ValueError('Unable to verify CPU features before opening native code')


def prepare(library, dependencies, output, cpu_isa):
    if ctypes.sizeof(Status) != 80 or ctypes.sizeof(Capabilities) != 248:
        raise ValueError('Native bundle preparation requires LP64 Linux x86_64')
    require_v3_cpu()
    if output.exists():
        raise ValueError('Bundle output must be a new directory')
    output.mkdir(parents=True)
    files = {}
    for source in [library] + dependencies:
        if not source.is_file() or source.is_symlink():
            raise ValueError('Supply regular native artifact files')
        name = 'libelasticpp.so' if source == library else source.name
        if name in files:
            raise ValueError('Duplicate native dependency basename')
        target = output / name
        shutil.copyfile(source, target)
        files[name] = digest(target)
    # This load is a mandatory provenance probe. It is not a substitute for
    # the JNA writer/reader integration tests required by publication tasks.
    native = ctypes.CDLL(str((output / 'libelasticpp.so').resolve()),
                         mode=os.RTLD_NOW | getattr(os, 'RTLD_NODELETE', 0))
    native.getNativeEsCapabilitiesV1.restype = ctypes.c_void_p
    native.freeNativeEsCapabilitiesV1.argtypes = [ctypes.c_void_p]
    pointer = native.getNativeEsCapabilitiesV1()
    if not pointer:
        raise ValueError('Native capability allocation failed')
    try:
        header = ctypes.cast(pointer, ctypes.POINTER(Status)).contents
        if header.abiVersion != 1 or header.structSize != ctypes.sizeof(Status) or header.success != 1 or header.errorCode != 0:
            raise ValueError('Native capability probe failed')
        value = ctypes.cast(pointer, ctypes.POINTER(Capabilities)).contents
        if value.minimumAbiVersion != 1 or value.maximumAbiVersion != 1 or value.capabilityFlags & 127 != 127:
            raise ValueError('Native writer/reader capability set differs')
        actual = bytes(value.sharedObjectSha256).hex()
        if actual != files['libelasticpp.so']:
            raise ValueError('Loaded native binary differs from the copied artifact')
        manifest = {'format': 'eslib-native-bundle-v1',
                    'cpuIsa': cpu_isa,
                    'elasticppCommit': bounded_string(value, 'gitCommit'),
                    'luceneVersion': bounded_string(value, 'luceneVersion'),
                    'targetTriple': bounded_string(value, 'targetTriple'),
                    'minimumGlibc': bounded_string(value, 'minimumGlibc'),
                    'sharedObjectSha256': actual, 'files': files}
    finally:
        native.freeNativeEsCapabilitiesV1(pointer)
    (output / 'native-manifest.json').write_text(json.dumps(manifest, sort_keys=True, indent=2) + '\n')
    validate(output)
    for file in output.iterdir():
        file.chmod(0o444)
    print('NATIVE_BUNDLE_PREPARED commit=' + manifest['elasticppCommit'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--dependency', type=Path, action='append', default=[])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cpu-isa', choices=['x86-64-v3'], required=True,
                        help='CPU contract from the frozen native build configuration')
    args = parser.parse_args()
    prepare(args.library, args.dependency, args.output, args.cpu_isa)
