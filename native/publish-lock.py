#!/usr/bin/env python3
"""Publish the small public source lock through an Actions annotation for API-only review."""
from pathlib import Path
import json

root=Path(__file__).resolve().parent/'xray'
files=[root/'go.mod',root/'go.sum',*root.rglob('*.go')]
text=json.dumps({str(p.relative_to(root)):p.read_text() for p in files},separators=(',',':'))
if len(text.encode()) > 60000:
    raise SystemExit('Native source lock exceeds annotation size')
text=text.replace('%','%25').replace('\r','%0D').replace('\n','%0A')
print('::notice title=Native source lock::'+text)
