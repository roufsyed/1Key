#!/usr/bin/env bash
# Refreshes the bundled snapshot of Google's privileged-browser allowlist used
# by the passkey provider (PASSKEYS_PLAN.md section 12). Developer-run only:
# the F-Droid build server has no network and the snapshot must be a committed
# file for reproducible builds. Prints the package and certificate diff so the
# change can be reviewed like code; a removed certificate is a security fix.
set -euo pipefail
cd "$(dirname "$0")/.."
URL="https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json"
ASSET="app/src/main/assets/passkeys/privileged_apps_google.json"
META="app/src/main/assets/passkeys/privileged_apps_google.meta.json"
TMP="$(mktemp)"
curl -sSfL --max-time 60 -o "$TMP" "$URL"
python3 - "$TMP" "$ASSET" "$META" "$URL" <<'PY'
import json, sys, hashlib, datetime
new_path, asset, meta, url = sys.argv[1:5]
raw = open(new_path, 'rb').read()
new = json.loads(raw)
assert isinstance(new.get('apps'), list) and len(new['apps']) > 40, "upstream list looks wrong, refusing"
def table(d):
    out = {}
    for a in d['apps']:
        if a.get('type') != 'android': continue
        out[a['info']['package_name']] = {s['cert_fingerprint_sha256'].upper() for s in a['info']['signatures']}
    return out
try:
    old = table(json.load(open(asset)))
except FileNotFoundError:
    old = {}
cur = table(new)
for p in sorted(set(cur) - set(old)): print(f"+ package {p}")
for p in sorted(set(old) - set(cur)): print(f"- package {p}   <-- REMOVED upstream: ship this refresh as a security fix")
for p in sorted(set(cur) & set(old)):
    for f in sorted(cur[p] - old[p]): print(f"+ cert {p} {f[:23]}...")
    for f in sorted(old[p] - cur[p]): print(f"- cert {p} {f[:23]}...   <-- REMOVED upstream")
open(asset, 'wb').write(raw)
json.dump({"source": url, "sha256": hashlib.sha256(raw).hexdigest(),
           "fetched_at": datetime.date.today().isoformat(), "entries": len(new['apps'])},
          open(meta, 'w'), indent=2)
open(meta, 'a').write("\n")
print(f"snapshot: {len(new['apps'])} entries, sha256 {hashlib.sha256(raw).hexdigest()[:16]}..., dated {datetime.date.today().isoformat()}")
PY
rm -f "$TMP"
echo "Review the diff above, then commit as: Refresh passkey browser allowlist"
