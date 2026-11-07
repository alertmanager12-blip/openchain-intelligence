#!/usr/bin/env bash
# Smoke test of the Docker Compose stack (demo mode, PostgreSQL persistence): starts it, checks it really works end to
# end, restarts the app and checks that operator state survived, stops it (the pgdata volume is kept).
# Usage: deploy/smoke-test.sh            (from the repository root; requires docker compose v2 and python3)
#        KEEP_RUNNING=1 deploy/smoke-test.sh   to leave the stack up afterwards.
# Catches failures that image builds alone do not: missing runtime modules in the JRE image, missing rule pack, etc.
set -euo pipefail

# Isolation from a real deployment: the smoke stack is a separate compose project (own containers and pgdata volume),
# always in demo mode with real chains off and no provisioned admin key, whatever a local .env holds. It still uses
# ports 8080/4200, so stop a running deployment first (`docker compose down`, data kept).
export COMPOSE_PROJECT_NAME=${SMOKE_PROJECT:-oci-smoke}
export OCI_DEMO_MODE=true OCI_CHAINS_ETHEREUM_ENABLED=false OCI_CHAINS_TRON_ENABLED=false OCI_ADMIN_API_KEY=
export OCI_SECURITY_API_KEY_PEPPER= OCI_CRYPTO_MASTER_KEY=b2NpLWRldi1vbmx5LW1hc3Rlci1rZXktMDAwMDAwMDA=
for p in 8080 4200; do
  if docker ps --format '{{.Ports}} {{.Label "com.docker.compose.project"}}' | grep -E ":$p->" | grep -vq " $COMPOSE_PROJECT_NAME\$"; then
    echo "SMOKE FAIL: port $p is used by another stack (stop it with 'docker compose down'; its data is kept)" >&2; exit 1
  fi
done

API=${API:-http://localhost:8080}
UI=${UI:-http://localhost:4200}
fail() { echo "SMOKE FAIL: $*" >&2; docker compose logs app --tail 80 >&2 || true; exit 1; }
cleanup() { [ "${KEEP_RUNNING:-0}" = 1 ] || docker compose down >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "== starting stack"
docker compose up -d --build --wait --wait-timeout 240 || fail "stack did not become healthy"

echo "== console page is fully styled under the production CSP"
# nginx sends script-src 'self': an inline <script> is blocked. Angular's critical-CSS inlining relied on one to switch
# the stylesheet from media=print to all, so production pages rendered almost unstyled. Guard against both.
index=$(curl -sf "$UI/") || fail "frontend index not served"
python3 - "$index" <<'PY' || fail "index.html would be broken by the CSP (inline script or print-only stylesheet)"
import re, sys
html = sys.argv[1]
inline = [s for s in re.findall(r'<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>', html, re.S) if s.strip()]
assert not inline, f"{len(inline)} inline script(s) blocked by script-src 'self'"
links = re.findall(r'<link[^>]*rel="stylesheet"[^>]*>', html)
assert links, "no stylesheet link"
assert not any('media="print"' in l for l in links), f"print-only stylesheet: {links}"
print("   no inline script, stylesheet loaded for all media")
PY

echo "== unauthenticated request must be rejected"
code=$(curl -s -o /dev/null -w '%{http_code}' "$API/api/v1/alerts")
[ "$code" = 401 ] || fail "expected 401 without API key, got $code"

# The demo admin key is printed once at startup (demo mode only). Never reuse this pattern outside demo mode.
# A new one is generated at every start (the previous one is revoked): always take the last one logged.
admin_key() { docker compose logs app 2>/dev/null | grep -oE 'shown once\): oci_[a-z]+_[A-Za-z0-9_]+' | tail -1 | awk '{print $3}'; }
key=$(admin_key)
[ -n "$key" ] || fail "demo admin key not found in logs"

echo "== persistence mode"
# (no `| grep -q`: with pipefail, grep exiting early makes the pipeline fail with SIGPIPE)
applogs=$(docker compose logs app 2>/dev/null)
grep -q 'Persistence mode: POSTGRESQL' <<<"$applogs" || fail "app is not running in PostgreSQL persistence mode"
grep -E 'Successfully applied|Schema .* is up to date' <<<"$applogs" | tail -1 | sed 's/.*: /   flyway: /'

echo "== rules loaded and dashboard served through the frontend proxy"
dash=$(curl -sf -H "X-API-Key: $key" "$UI/api/v1/dashboard") || fail "dashboard via frontend proxy failed"
python3 - "$dash" <<'PY' || fail "dashboard checks failed"
import json, sys
t = json.loads(sys.argv[1])["totals"]
assert t["rules"] > 0, f"no rules loaded: {t}"
assert t["eventsIndexed"] > 0, f"nothing indexed: {t}"
print("   totals:", t)
PY

echo "== demo scenarios A-F: event -> signal -> risk -> rule -> alert -> notification"
scen=""
for _ in $(seq 1 20); do
  scen=$(curl -sf -H "X-API-Key: $key" "$API/api/v1/demo/scenarios") && \
    python3 -c 'import json,sys; d=json.loads(sys.argv[1]); d=d.get("items",d); sys.exit(0 if len(d)==6 and all(all(s["checks"].values()) for s in d) else 1)' "$scen" && break
  sleep 3
done
python3 - "$scen" <<'PY' || fail "demo scenario checks failed"
import json, sys
d = json.loads(sys.argv[1]); d = d.get("items", d)
bad = [(s["id"], {k: v for k, v in s["checks"].items() if not v}) for s in d if not all(s["checks"].values())]
for s in d:
    print(f"   {s['id']} {s['title']}: alerts={len(s['alerts'])}")
assert len(d) == 6 and not bad, f"failing scenarios: {bad}"
f = next(s for s in d if s["id"] == "F")
types = {e.get("type") for e in f["events"]}
assert {"STABLECOIN_FREEZE", "STABLECOIN_UNFREEZE"} <= types, f"scenario F freeze/unfreeze not indexed: {f['events']}"
assert any(a["ruleId"] == "issuer-freeze-counterparty" for a in f["alerts"]), f"scenario F alert missing: {f['alerts']}"
print("   F issuer freeze: freeze + unfreeze indexed, issuer-freeze-counterparty alert raised")
PY

scenarios_ok() {
  python3 -c 'import json,sys; d=json.loads(sys.argv[1]); d=d.get("items",d); sys.exit(0 if len(d)==6 and all(all(s["checks"].values()) for s in d) else 1)' "$1"
}
api() { curl -s -H "X-API-Key: $1" -H 'Content-Type: application/json' -X "$2" "$API$3" ${4:+--data "$4"}; }
# jget JSON dotted.path  (numeric segments index lists)
jget() { python3 -c 'import json,sys
d=json.loads(sys.argv[1])
for k in sys.argv[2].split("."): d=d[int(k)] if k.isdigit() else d[k]
print(d)' "$1" "$2"; }

echo "== operator state written before a restart"
run=$(date +%s)
created=$(api "$key" POST /api/v1/api-keys '{"name":"smoke-persist","scopes":["alerts:read","webhooks:read","rules:read"],"expiresInDays":1}')
pkey=$(jget "$created" secret) || fail "API key creation failed: $created"
pkey_id=$(jget "$created" id)
hook=$(api "$key" POST /api/v1/webhooks '{"name":"smoke-persist","url":"https://hooks.example.com/smoke?token=SMOKETOKEN","events":["alert.opened"]}')
hook_id=$(jget "$hook" id) || fail "connector creation failed: $hook"
rule_id="smoke-persist-$run"
rule_yaml="apiVersion: openchain.io/rule/v1
id: $rule_id
version: 1.0.0
name: Smoke persistence
author: smoke
enabled: false
scope:
  networks: [tron]
  eventTypes: [TOKEN_TRANSFER]
conditions:
  logic: AND
  rules:
    - field: transaction.amount
      operator: GREATER_THAN_OR_EQUAL
      value: \"999999999999\"
severity: LOW
evidenceLevel: ANOMALY
cooldown: 10m
documentation: smoke test rule
examples:
  - name: big
    expect: MATCH
    event: { fields: { transaction.amount: \"1000000000000\" } }
"
rule=$(api "$key" POST /api/v1/rules "$(python3 -c 'import json,sys; print(json.dumps({"source": sys.argv[1]}))' "$rule_yaml")")
[ "$(jget "$rule" id)" = "$rule_id" ] || fail "rule creation failed: $rule"
[ "$(jget "$(api "$key" POST "/api/v1/rules/$rule_id/enable")" enabled)" = True ] || fail "rule enable failed"
alerts=$(api "$key" GET '/api/v1/alerts?status=OPEN')
alert_id=$(jget "$alerts" items.0.id) || fail "no open alert to acknowledge: $alerts"
[ "$(jget "$(api "$key" POST "/api/v1/alerts/$alert_id/acknowledge")" status)" = ACKNOWLEDGED ] || fail "acknowledge failed"
echo "   key ${pkey%_*}_***, connector $hook_id, rule $rule_id (enabled), alert $alert_id acknowledged"

echo "== docker compose restart app"
docker compose restart app >/dev/null || fail "restart failed"
healthy=0
for _ in $(seq 1 60); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' "$API/actuator/health")" = 200 ]; then healthy=1; break; fi
  sleep 3
done
[ "$healthy" = 1 ] || fail "app not healthy after restart"

echo "== operator state after the restart"
[ "$(jget "$(api "$pkey" GET "/api/v1/alerts/$alert_id")" status)" = ACKNOWLEDGED ] || fail "key or acknowledged alert lost after restart"
hooks=$(api "$pkey" GET /api/v1/webhooks)
python3 -c 'import json,sys
h=[x for x in json.loads(sys.argv[1]) if x["id"]==sys.argv[2]]
assert h and h[0]["url"]=="https://hooks.example.com/…" and "SMOKETOKEN" not in sys.argv[1], h' "$hooks" "$hook_id" \
  || fail "connector lost or unmasked after restart: $hooks"
rule=$(api "$pkey" GET "/api/v1/rules/$rule_id")
{ [ "$(jget "$rule" enabled)" = True ] && [ "$(jget "$rule" version)" = 1 ]; } || fail "rule state lost after restart: $rule"
newkey=$(admin_key)
[ -n "$newkey" ] && [ "$newkey" != "$key" ] || fail "no new demo admin key after restart"
[ "$(curl -s -o /dev/null -w '%{http_code}' -H "X-API-Key: $key" "$API/api/v1/alerts")" = 401 ] || fail "previous demo admin key still valid"
echo "   key, connector (masked), rule (enabled, v1) and acknowledged alert survived; previous demo admin key revoked"
key=$newkey
scen=""
for _ in $(seq 1 20); do
  scen=$(curl -sf -H "X-API-Key: $key" "$API/api/v1/demo/scenarios") && scenarios_ok "$scen" && break
  sleep 3
done
scenarios_ok "$scen" || fail "demo scenarios failing after restart: $scen"
echo "   demo scenarios A-F still pass after the restart"

echo "== cleanup of the smoke objects (the pgdata volume is kept)"
api "$key" POST "/api/v1/api-keys/$pkey_id/revoke" >/dev/null
api "$key" DELETE "/api/v1/webhooks/$hook_id" >/dev/null
api "$key" DELETE "/api/v1/rules/$rule_id" >/dev/null

echo "SMOKE OK"
