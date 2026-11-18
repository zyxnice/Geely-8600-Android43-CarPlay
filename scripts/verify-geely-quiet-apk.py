"""Verify logging removal in the actual optimized APK, rather than debug unit-test classes.

Usage: python scripts/verify-geely-quiet-apk.py NOLOG.apk --logged LOG.apk
Uses DEX reference tables: absence of an output method reference proves no direct invocation
can remain. It does not claim to suppress Android/OEM logs outside the APK.
"""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path

MARKERS = (
    "pcmNonZeroSamples=", "pcmInspectTotalUs=", "audio stats audioType=",
    "pipeline avg/max", "Receive: ", "diplay-diagnostic-writer", "DiPlay log started ",
)
OUTPUTS = {
    "Landroid/util/Log;": {"v", "d", "i", "w", "e", "wtf", "println"},
    "Ljava/util/logging/Logger;": {
        "log", "logp", "logrb", "finest", "finer", "fine", "config", "info",
        "warning", "severe", "entering", "exiting", "throwing",
    },
}


def inspect_apk(path):
    blob = path.read_bytes()
    with zipfile.ZipFile(path) as archive:
        dex_names = [name for name in archive.namelist() if name.endswith(".dex") and "/" not in name]
        assert dex_names == ["classes.dex"], dex_names
        data = archive.read("classes.dex")
    assert data.startswith(b"dex\n035\0"), "API18 requires DEX035"

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def string_at(offset):
        # Skip the ULEB128 UTF-16 length; diagnostic markers are ASCII in MUTF-8.
        while data[offset] & 128:
            offset += 1
        offset += 1
        return data[offset:data.index(b"\0", offset)].decode("utf-8", errors="replace")

    strings = [string_at(u32(u32(60) + 4 * i)) for i in range(u32(56))]
    types = [strings[u32(u32(68) + 4 * i)] for i in range(u32(64))]
    references = set()
    for i in range(u32(88)):
        owner, _, name = struct.unpack_from("<HHI", data, u32(92) + 8 * i)
        if strings[name] in OUTPUTS.get(types[owner], set()):
            references.add(types[owner] + "->" + strings[name])
    return {
        "apk": str(path), "sha256": hashlib.sha256(blob).hexdigest(),
        "logging_output_method_refs": sorted(references),
        "diagnostic_markers": {marker: any(marker in value for value in strings) for marker in MARKERS},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("quiet", type=Path)
    parser.add_argument("--logged", type=Path, required=True)
    args = parser.parse_args()
    quiet, logged = inspect_apk(args.quiet), inspect_apk(args.logged)
    print(json.dumps({"quiet": quiet, "logged": logged}, ensure_ascii=False, indent=2))
    assert not quiet["logging_output_method_refs"], "Quiet APK still references logging output methods"
    assert not any(quiet["diagnostic_markers"].values()), "Diagnostic hot paths remain in quiet APK"
    assert logged["logging_output_method_refs"], "Logged control must retain logging"
    assert all(logged["diagnostic_markers"].values()), "Logged control lost expected diagnostics"


if __name__ == "__main__":
    main()
