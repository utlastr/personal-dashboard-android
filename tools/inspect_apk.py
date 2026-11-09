"""Inspect packaged ABIs and native ELF alignment without executing binaries."""
from pathlib import Path
import struct
import zipfile
import json

output = Path(__file__).resolve().parents[1]/"output"
apk = output / json.loads((output/"update-manifest.json").read_text(encoding="utf-8-sig"))["apk"]
with zipfile.ZipFile(apk) as archive:
    for name in archive.namelist():
        if not name.endswith(".so"):
            continue
        data = archive.read(name)
        assert data[:5] == b"\x7fELF\x02", name
        offset = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
        alignments = []
        for index in range(count):
            header = struct.unpack_from("<IIQQQQQQ", data, offset+index*size)
            if header[0] == 1:
                alignments.append(header[-1])
        print(name, "LOAD alignments", alignments)
        assert min(alignments) >= 16384, "Native library lacks 16 KB page alignment"
    assert "assets/dashboard/index.html" in archive.namelist()
    assert "assets/dashboard/mobile.html" in archive.namelist()
    assert "assets/dashboard/logo.png" in archive.namelist()
    assert not any("keystore" in n or n.endswith(".jks") or "pairing-token" in n for n in archive.namelist())
print("PASS: APK assets, native 16 KB page alignment, no private signing/pairing files")
