#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/native/versions.env"
DEST="$ROOT/.work/hev"
if [[ ! -d "$DEST/.git" ]]; then
  mkdir -p "$DEST"
  git -C "$DEST" init -q
  git -C "$DEST" remote add origin https://github.com/heiher/hev-socks5-tunnel.git
  git -C "$DEST" fetch --depth 1 origin "$HEV_COMMIT"
  git -C "$DEST" checkout --detach FETCH_HEAD
fi
test "$(git -C "$DEST" rev-parse HEAD)" = "$HEV_COMMIT"
git -C "$DEST" submodule update --init --recursive --depth 1
cp "$ROOT/native/hev/wot-jni.c" "$DEST/src/hev-jni.c"
python3 - "$DEST/src/hev-socks5-tunnel.c" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
text = path.read_text()
if 'wot_tunnel_ready' not in text:
    anchor = '    WRITE_ONCE (run, 1);'
    assert text.count(anchor) == 1, 'Pinned Hev readiness hook changed'
    text = text.replace(anchor, anchor + '\n#ifdef ANDROID\n    extern void wot_tunnel_ready(void);\n    wot_tunnel_ready();\n#endif')
    path.write_text(text)
PY
