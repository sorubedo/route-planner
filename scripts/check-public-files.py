#!/usr/bin/env python3
"""Fail a release if source or APK contains credentials or private signing files."""
import pathlib
import re
import sys
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
key_pattern = re.compile(rb"(?<![A-Z0-9])[A-Z0-9]{5}(?:-[A-Z0-9]{5}){5}(?![A-Z0-9])")
failed = []
for path in (root / "app" / "src" / "main").rglob("*"):
    if path.is_file() and key_pattern.search(path.read_bytes()):
        failed.append(str(path.relative_to(root)))
for name in sys.argv[1:]:
    with zipfile.ZipFile(name) as apk:
        for entry in apk.infolist():
            if entry.filename.endswith((".jks", ".keystore", "signing.properties")) or key_pattern.search(apk.read(entry)):
                failed.append(f"{pathlib.Path(name).name}:{entry.filename}")
if failed:
    raise SystemExit("Possible credentials in public files: " + ", ".join(failed))
print("Public source and APK credential check passed")
