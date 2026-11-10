#!/usr/bin/env python3
"""Bundle the exact native dependency notices into the APK assets."""
import json
import shutil
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
output = root / 'app/build/generated/native/assets/native-licenses'
output.mkdir(parents=True, exist_ok=True)
raw = subprocess.check_output(['go', 'list', '-m', '-json', 'all'], cwd=root / 'native/xray').decode()
decoder = json.JSONDecoder()
modules = []
while raw.strip():
    item, end = decoder.raw_decode(raw.lstrip())
    raw = raw.lstrip()[end:]
    modules.append(item)
locations = [(m['Path'].replace('/', '_'), Path(m['Dir'])) for m in modules if m.get('Dir')]
locations += [('go', Path(subprocess.check_output(['go', 'env', 'GOROOT']).decode().strip()))]
hev = root / '.work/hev'
locations += [('hev_' + str(p.relative_to(hev)).replace('/', '_'), p.parent)
              for p in hev.rglob('LICENSE*') if '.git' not in p.parts]
for name, directory in locations:
    for pattern in ('LICENSE*', 'COPYING*', 'NOTICE*'):
        for source in directory.glob(pattern):
            if source.is_file():
                target = output / name / source.name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
shutil.copyfile(root / 'native/versions.env', output / 'versions.env')
(output / 'modules.json').write_text(json.dumps(
    [{'module': m['Path'], 'version': m.get('Version', 'local'), 'sum': m.get('Sum', '')} for m in modules], indent=2,
))
