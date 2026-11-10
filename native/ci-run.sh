#!/usr/bin/env bash
# Keep the failure tail in an Actions annotation as well as the normal CI log.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
name=$1
shift
mkdir -p "$ROOT/.work/diagnostics"
log="$ROOT/.work/diagnostics/$name.log"
"$@" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
if [[ "$status" != 0 ]]; then
  python3 - "$log" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text(errors='replace')[-22000:]
chunks = [text[i:i + 2800] for i in range(0, len(text), 2800)]
for index, chunk in enumerate(chunks, 1):
    chunk = chunk.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f'::error title=Build diagnostics {index:02d}/{len(chunks):02d}::{chunk}')
PY
fi
exit "$status"
