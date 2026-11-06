#!/usr/bin/env bash
# Smoke test of the Docker Compose stack (demo mode): starts it, checks it really works end to end, stops it.
# Usage: deploy/smoke-test.sh            (from the repository root; requires docker compose v2 and python3)
#        KEEP_RUNNING=1 deploy/smoke-test.sh   to leave the stack up afterwards.
# Catches failures that image builds alone do not: missing runtime modules in the JRE image, missing rule pack, etc.
set -euo pipefail

API=${API:-http://localhost:8080}
UI=${UI:-http://localhost:4200}
fail() { echo "SMOKE FAIL: $*" >&2; docker compose logs app --tail 80 >&2 || true; exit 1; }
cleanup() { [ "${KEEP_RUNNING:-0}" = 1 ] || docker compose down >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "== starting stack"
OCI_DEMO_MODE=true docker compose up -d --build --wait --wait-timeout 240 || fail "stack did not become healthy"

echo "== unauthenticated request must be rejected"
code=$(curl -s -o /dev/null -w '%{http_code}' "$API/api/v1/alerts")
[ "$code" = 401 ] || fail "expected 401 without API key, got $code"

# The demo admin key is printed once at startup (demo mode only). Never reuse this pattern outside demo mode.
key=$(docker compose logs app 2>/dev/null | grep -oE 'shown once\): oci_[a-z]+_[A-Za-z0-9_]+' | tail -1 | awk '{print $3}')
[ -n "$key" ] || fail "demo admin key not found in logs"

echo "== rules loaded and dashboard served through the frontend proxy"
dash=$(curl -sf -H "X-API-Key: $key" "$UI/api/v1/dashboard") || fail "dashboard via frontend proxy failed"
python3 - "$dash" <<'PY' || fail "dashboard checks failed"
import json, sys
t = json.loads(sys.argv[1])["totals"]
assert t["rules"] > 0, f"no rules loaded: {t}"
assert t["eventsIndexed"] > 0, f"nothing indexed: {t}"
print("   totals:", t)
PY

echo "== demo scenarios A-E: event -> signal -> risk -> rule -> alert -> notification"
scen=""
for _ in $(seq 1 20); do
  scen=$(curl -sf -H "X-API-Key: $key" "$API/api/v1/demo/scenarios") && \
    python3 -c 'import json,sys; d=json.loads(sys.argv[1]); d=d.get("items",d); sys.exit(0 if len(d)==5 and all(all(s["checks"].values()) for s in d) else 1)' "$scen" && break
  sleep 3
done
python3 - "$scen" <<'PY' || fail "demo scenario checks failed"
import json, sys
d = json.loads(sys.argv[1]); d = d.get("items", d)
bad = [(s["id"], {k: v for k, v in s["checks"].items() if not v}) for s in d if not all(s["checks"].values())]
for s in d:
    print(f"   {s['id']} {s['title']}: alerts={len(s['alerts'])}")
assert len(d) == 5 and not bad, f"failing scenarios: {bad}"
PY

echo "SMOKE OK"
