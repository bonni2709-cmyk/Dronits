#!/usr/bin/env python3
"""Expose the public source lock in small annotations for API-only CI review."""
from pathlib import Path
import base64
import gzip
import json

root = Path(__file__).resolve().parent / 'xray'
files = [root / 'go.mod', root / 'go.sum', *root.rglob('*.go')]
text = json.dumps({str(p.relative_to(root)): p.read_text() for p in files}, separators=(',', ':'))
encoded = base64.b64encode(gzip.compress(text.encode(), mtime=0)).decode()
chunks = [encoded[i:i + 3000] for i in range(0, len(encoded), 3000)]
for index, chunk in enumerate(chunks, 1):
    print(f'::notice title=Native source lock {index:02d}/{len(chunks):02d}::{chunk}')
