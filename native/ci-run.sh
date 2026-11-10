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
text=Path(sys.argv[1]).read_text(errors='replace')[-22000:]
text=text.replace('%','%25').replace('\r','%0D').replace('\n','%0A')
print('::error title=Build diagnostics::'+text)
PY
fi
exit "$status"
