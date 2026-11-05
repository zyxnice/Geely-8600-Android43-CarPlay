import argparse
import hashlib
import json
import re
import struct
from pathlib import Path
from zipfile import ZipFile


def inspect(apk):
    with ZipFile(apk) as archive:
        dex = []
        for name in sorted(archive.namelist()):
            if not re.fullmatch(r"classes(?:[0-9]+)?\.dex", name):
                continue
            data = archive.read(name)
            if len(data) < 112 or data[:8] != b"dex\n035\0":
                raise ValueError(f"{apk.name}: {name} is not DEX035")
            dex.append({
                "name": name,
                "bytes": len(data),
                "method_references": struct.unpack_from("<I", data, 88)[0],
                "classes": struct.unpack_from("<I", data, 96)[0],
            })
        if not dex:
            raise ValueError(f"{apk.name}: no application DEX found")
        required = {
            "assets/geely-usb/helper.dex",
            "assets/offline-mfi/identity.pk8",
            "assets/offline-mfi/certificate.p7b",
        }
        required.update(name for name in archive.namelist()
                        if name.startswith("lib/") and name.endswith(".so"))
        if "lib/armeabi-v7a/libxcertplay_chacha.so" not in required:
            raise ValueError(f"{apk.name}: USB3-B ARMv7 native media library missing")
        protected = {}
        for name in sorted(required):
            data = archive.read(name)
            if not data:
                raise ValueError(f"{apk.name}: empty required entry {name}")
            if name.endswith("helper.dex") and data[:8] != b"dex\n035\0":
                raise ValueError(f"{apk.name}: helper is not DEX035")
            protected[name] = hashlib.sha256(data).hexdigest()
    summary = {
        "apk": str(apk),
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "apk_bytes": apk.stat().st_size,
        "dex": dex,
        "dex_bytes": sum(entry["bytes"] for entry in dex),
    }
    return summary, protected


def compare(baseline, candidate):
    before, before_protected = inspect(baseline)
    after, after_protected = inspect(candidate)
    if [entry["name"] for entry in after["dex"]] != ["classes.dex"]:
        raise ValueError("Candidate must contain one application DEX to avoid secondary extraction")
    if before_protected != after_protected:
        raise ValueError("Native libraries, standalone helper or authentication assets changed")
    if after["dex_bytes"] >= before["dex_bytes"]:
        raise ValueError("Candidate DEX size did not decrease")
    if after["apk_bytes"] >= before["apk_bytes"]:
        raise ValueError("Candidate APK size did not decrease")
    return {
        "baseline": before,
        "candidate": after,
        "protected_entries_identical": True,
        "apk_reduction_percent": round(100 * (1 - after["apk_bytes"] / before["apk_bytes"]), 2),
        "dex_reduction_percent": round(100 * (1 - after["dex_bytes"] / before["dex_bytes"]), 2),
        "scope": "Packaging only; signature, manifest and on-device startup need separate verification",
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = compare(args.baseline, args.candidate)
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.report:
        args.report.write_text(text + "\n", encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
