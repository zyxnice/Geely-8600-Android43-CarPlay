"""Build an OFFLINE H53 button-only speech candidate; never accesses a device.

Only seven ma wake-start instruction arrays and the embedded DEX Adler32 change.
ODEX original-Dex SHA1, dependencies, preverification flags, class/method layout,
APK signature, and every other byte are preserved. This is not runtime acceptance.
"""
from pathlib import Path
import argparse
import hashlib
import json
import struct
import zlib

if not __debug__:
    raise RuntimeError('Do not use Python -O: this fixed-firmware builder requires its assertions')

EXPECTED = '84fa3b983da21d7c52d8703cea85efa467a4f70263eb0ff21b991026e35c4bd8'
# Refusal, not fake successful activation. External code 3 is the existing
# speech-off/not-available result; its Binder clients still need live observation.
TARGETS = {
    'a(Ljava/lang/String;Ljava/util/List;Z)I': 3,
    'a(Ljava/lang/String;Ljava/lang/String;)V': None,
    'b(Ljava/lang/String;)V': None,
    'c(Ljava/lang/String;)V': None,
    'c()Z': 0,
    'd(Ljava/lang/String;)I': -1,
    'e()V': None,
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


class Dex:
    def __init__(self, data):
        self.data = data
        assert data[:8] == b'dex\n035\0'
        assert self.u32(32) == len(data)
        assert self.u32(36) == 112 and self.u32(40) == 0x12345678
        self.strings = []
        for i in range(self.u32(56)):
            offset = self.u32(self.u32(60) + i * 4)
            _, start = self.uleb(offset)
            end = data.index(0, start)
            # Only ASCII class/method descriptors select patch targets. Preserve
            # unrelated modified UTF-8 strings byte-for-byte in the artifact.
            self.strings.append(data[start:end].decode('utf-8', errors='replace'))
        self.types = [self.strings[self.u32(self.u32(68) + i * 4)]
                      for i in range(self.u32(64))]
        self.protos = []
        for i in range(self.u32(72)):
            offset = self.u32(76) + i * 12
            return_type, params = self.u32(offset + 4), self.u32(offset + 8)
            args = [] if not params else [self.types[self.u16(params + 4 + j * 2)]
                                          for j in range(self.u32(params))]
            self.protos.append('(' + ''.join(args) + ')' + self.types[return_type])
        self.methods = []
        for i in range(self.u32(88)):
            offset = self.u32(92) + i * 8
            self.methods.append((self.types[self.u16(offset)],
                                 self.strings[self.u32(offset + 4)] +
                                 self.protos[self.u16(offset + 2)]))

    def u16(self, offset):
        return struct.unpack_from('<H', self.data, offset)[0]

    def u32(self, offset):
        return struct.unpack_from('<I', self.data, offset)[0]

    def uleb(self, offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = self.data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
        raise ValueError('Invalid uleb128')

    def class_methods(self, name):
        for index in range(self.u32(96)):
            offset = self.u32(100) + index * 32
            if self.types[self.u32(offset)] != name:
                continue
            cursor = self.u32(offset + 24)
            counts = []
            for _ in range(4):
                value, cursor = self.uleb(cursor)
                counts.append(value)
            for _ in range(counts[0] + counts[1]):
                _, cursor = self.uleb(cursor)
                _, cursor = self.uleb(cursor)
            result = {}
            for count in counts[2:]:
                method_index = 0
                for _ in range(count):
                    delta, cursor = self.uleb(cursor)
                    flags, cursor = self.uleb(cursor)
                    code_offset, cursor = self.uleb(cursor)
                    method_index += delta
                    owner, signature = self.methods[method_index]
                    assert owner == name and signature not in result
                    result[signature] = (code_offset, flags, method_index)
            return result
        raise ValueError('Class not found: ' + name)


def build(original, output_dir):
    if output_dir.exists():
        raise FileExistsError('Use a new external output directory; existing evidence is not overwritten')
    source = original.read_bytes()
    assert sha(source) == EXPECTED, 'Only the verified original H53 ODEX is accepted'
    assert source[:8] == b'dey\n036\0'
    dex_off, dex_len, dep_off, dep_len, opt_off, opt_len, flags, opt_sum = struct.unpack_from('<8I', source, 8)
    assert 40 <= dex_off < dex_off + dex_len <= dep_off
    assert dep_off + dep_len <= opt_off and opt_off + opt_len <= len(source)
    dex = Dex(source[dex_off:dex_off + dex_len])
    assert zlib.adler32(dex.data[12:]) & 0xffffffff == dex.u32(8)
    assert zlib.adler32(source[dep_off:opt_off + opt_len]) & 0xffffffff == opt_sum
    methods = dex.class_methods('Lma;')
    candidate = bytearray(source)
    permitted = {(dex_off + 8, dex_off + 12)}
    receipt_methods = []
    evidence = []
    for signature, result in TARGETS.items():
        code_offset, access, method_id = methods[signature]
        assert code_offset and code_offset % 4 == 0
        registers, incoming, outgoing, tries, debug_offset, units = struct.unpack_from('<4H2I', dex.data, code_offset)
        assert tries == 0, 'Do not alter methods with exception ranges'
        assert registers > incoming and units >= 3
        start = dex_off + code_offset + 16
        end = start + units * 2
        assert end <= dex_off + dex_len
        # DEX 10x return-void; or 11n const/4 v0 followed by 11x return v0.
        # Fill dead instructions with valid nop words. Retain a final return so
        # even structural scanners cannot interpret the tail as falling off.
        words = [0] * units
        if result is None:
            words[0] = words[-1] = 0x000e
            listing = ['return-void']
        else:
            assert -8 <= result <= 7
            words[0] = ((result & 15) << 12) | 0x12
            words[1] = words[-1] = 0x000f
            listing = [f'const/4 v0, {result}', 'return v0']
        replacement = struct.pack('<' + 'H' * units, *words)
        candidate[start:end] = replacement
        permitted.add((start, end))
        receipt_methods.append(dict(signature=signature, method_id=method_id,
                                    code_offset=code_offset, odex_start=start,
                                    odex_end_exclusive=end, registers=registers,
                                    incoming=incoming, outgoing=outgoing,
                                    debug_offset=debug_offset, access=access,
                                    instruction_units=units, return_value=result,
                                    original_body_sha256=sha(source[start:end]),
                                    patched_body_sha256=sha(replacement)))
        evidence += [f'.method {signature}', f'  # ODEX [{start:#x},{end:#x}), {units} code units']
        evidence += ['  ' + line for line in listing]
        evidence += [f'  # remaining words: nop, final {listing[-1]}', '.end method', '']
    new_sum = zlib.adler32(candidate[dex_off + 12:dex_off + dex_len]) & 0xffffffff
    struct.pack_into('<I', candidate, dex_off + 8, new_sum)
    changes = [i for i, (left, right) in enumerate(zip(source, candidate)) if left != right]
    assert len(source) == len(candidate)
    assert all(any(start <= i < end for start, end in permitted) for i in changes)
    assert candidate[dex_off + 12:dex_off + 32] == source[dex_off + 12:dex_off + 32]
    assert candidate[dep_off:] == source[dep_off:]
    assert zlib.adler32(candidate[dep_off:opt_off + opt_len]) & 0xffffffff == opt_sum
    for signature, (code_offset, _, _) in methods.items():
        if signature in TARGETS or not code_offset:
            continue
        units = dex.u32(code_offset + 12)
        start = dex_off + code_offset
        assert candidate[start:start + 16 + units * 2] == source[start:start + 16 + units * 2]
    # Build a compressed exact-byte change manifest in addition to allowed spans.
    spans = []
    for offset in changes:
        if spans and spans[-1][1] == offset:
            spans[-1][1] += 1
        else:
            spans.append([offset, offset + 1])
    output_dir.mkdir(parents=True, exist_ok=True)
    filename = 'XCSpeechClient-button-only-' + sha(candidate)[:12] + '.odex'
    target = output_dir / filename
    target.write_bytes(candidate)
    assert sha(target.read_bytes()) == sha(candidate)
    receipt = dict(original_path=str(original.resolve()), original_sha256=EXPECTED,
                   candidate_path=str(target.resolve()), candidate_sha256=sha(candidate),
                   bytes=len(candidate), changed_bytes=len(changes), changed_spans=spans,
                   allowed_spans=sorted(permitted), dex_offset=dex_off, dex_length=dex_len,
                   original_dex_adler32=hex(dex.u32(8)), candidate_dex_adler32=hex(new_sum),
                   original_dex_sha1_preserved=dex.data[12:32].hex(),
                   odex_opt_adler32_unchanged=hex(opt_sum), odex_flags=flags,
                   methods=receipt_methods, runtime_validation='NOT INSTALLED OR TESTED',
                   logging='No new runtime logging: original instruction pool and all metadata preserved',
                   limitations=['External Binder clients may retry refusal code 3; observe live.',
                                'All wake scenes disabled, including spoken follow-up confirmation/selection.',
                                'Original source identity preserved as required for optimized DEX; candidate SHA256 is new identity.',
                                'Original debug line mapping preserved and no longer describes patched method bodies.'],
                   sources=['https://android.googlesource.com/platform/dalvik.git/+/48a2061356d1269fbd04420d9095ca7dd5e54901/libdex/DexFile.cpp',
                            'https://android.googlesource.com/platform/dalvik/+/3740ab6c5b5544e26acb257bc092415b48bdef63/vm/analysis/DexPrepare.cpp'])
    (output_dir / 'candidate-receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding='utf-8')
    (output_dir / 'candidate-instructions.txt').write_text('\n'.join(evidence), encoding='utf-8')
    print(json.dumps({key: receipt[key] for key in ['candidate_path', 'candidate_sha256', 'changed_bytes', 'bytes']}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--original', type=Path, required=True, help='External verified OEM ODEX; never stored in Git')
    parser.add_argument('--output', type=Path, required=True, help='External directory for candidate and offline receipt')
    args = parser.parse_args()
    build(args.original, args.output)
