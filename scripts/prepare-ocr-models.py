#!/usr/bin/env python3
"""Prepare pinned official OCR assets using Python 3.10+ stdlib; --local-directory avoids networking."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import urllib.request


def prepare(local_directory=None):
    assets = Path(__file__).resolve().parent.parent / "app/src/ocrBundled/assets/ocr"
    manifest = json.loads((assets / "manifest.json").read_text(encoding="utf-8"))
    for name in ("chi_sim", "chi_tra", "eng"):
        item = manifest["models"][name]
        target = assets / f"{name}.traineddata"
        if target.is_file() and target.stat().st_size == item["bytes"] and hashlib.sha256(target.read_bytes()).hexdigest() == item["sha256"]:
            print(f"{name}: already verified")
            continue
        fd, temporary = tempfile.mkstemp(prefix="model-", suffix=".tmp", dir=assets)
        try:
            with os.fdopen(fd, "wb") as output:
                if local_directory:
                    with (Path(local_directory) / f"{name}.traineddata").open("rb") as source:
                        shutil.copyfileobj(source, output)
                else:
                    url = f"https://raw.githubusercontent.com/{manifest['repository']}/{manifest['commit']}/{name}.traineddata"
                    print(f"Downloading {name} from {manifest['commit']}")
                    with urllib.request.urlopen(url, timeout=120) as source:
                        shutil.copyfileobj(source, output)
            data = Path(temporary).read_bytes()
            if len(data) != item["bytes"] or hashlib.sha256(data).hexdigest() != item["sha256"]:
                raise ValueError(f"Model verification failed: {name}")
            os.replace(temporary, target)
            print(f"{name}: verified")
        finally:
            Path(temporary).unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local-directory", help="Use existing files without network access")
    prepare(parser.parse_args().local_directory)
