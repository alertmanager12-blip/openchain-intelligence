# Quickstart (demo mode)

Demo mode runs OCI against **simulated chains** (TRON and Ethereum): a deterministic generator produces blocks with USDT/USDC transfers, plus five scripted scenarios that go through the real pipeline (indexer → detection → risk scoring → rule engine → alert → notification). No external provider, API key or internet access is needed. **All demo addresses, transfers and labels are fictitious** and flagged as such (`fictitious: true`).

## 1. Start

```bash
docker compose up -d --build
curl -s localhost:8080/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}
```

Every API call needs an API key. Demo mode generates an all-permissions admin key valid 24 h and prints it **once** in the app logs:

```bash
docker compose logs app | grep "demo admin API key"
export OCI_KEY=oci_...      # copy the key from the log line
```

Open http://localhost:4200, go to **Settings → Connection**, choose authentication "API key" and paste the key. (When running from source with `mvn -pl oci-app spring-boot:run`, the `demo` profile also provides a documented read/analyst key for the console: see `oci-app/README.md`.)

`bash deploy/smoke-test.sh` runs all the checks below automatically.

## 2. Check networks and the dashboard

```bash
curl -s -H "X-API-Key: $OCI_KEY" localhost:8080/api/v1/networks | jq
curl -s -H "X-API-Key: $OCI_KEY" localhost:8080/api/v1/dashboard | jq .totals
```

Both networks are simulated (`simulated: true`); the head advances continuously.

## 3. Follow a demo scenario

```bash
curl -s -H "X-API-Key: $OCI_KEY" localhost:8080/api/v1/demo/scenarios | jq '.items[] | {id, title, expectedRule, expectedSignal, checks}'
```

| Scenario | Story | Expected rule | Expected signal |
|---|---|---|---|
| A | Large USDT transfer | `large-usdt-transfer` | `UNUSUAL_AMOUNT` |
| B | Indirect exposure (2 hops) to a listed address | `sanctioned-exposure-signal` | `INDIRECT_EXPOSURE` |
| C | Wallet draining | `wallet-draining` | `RAPID_DRAIN` |
| D | Fund splitting (fan-out) | `fund-splitting` | `FLOW_CONCENTRATION` |
| E | Dormant wallet reactivation | `dormant-wallet-reactivation` | `DORMANT_REACTIVATION` |

Each scenario lists its addresses (`roles`), the indexed events, the signals, the risk score, the alerts and the simulated notifications. Analyse one of its wallets:

```bash
W=$(curl -s -H "X-API-Key: $OCI_KEY" localhost:8080/api/v1/demo/scenarios | jq -r '.items[] | select(.id=="C") | .roles.wallet')
curl -s -H "X-API-Key: $OCI_KEY" "localhost:8080/api/v1/wallets/$W/analysis?network=tron" | jq '{risk, signals: [.signals[] | {type, severity, evidenceLevel}]}'
```

Each risk contribution is attributed to a signal with its evidence and evidence level. Simulated notifications (demo `default-webhook`) are listed by `GET /api/v1/demo/notifications`.

## 4. Create a rule and test it (DRY_RUN)

```bash
cat > burst.yaml <<'EOF'
apiVersion: openchain.io/rule/v1
id: demo-usdt-burst
version: 1.0.0
name: Demo USDT burst
author: quickstart
scope:
  networks: [tron]
  eventTypes: [TOKEN_TRANSFER]
conditions:
  logic: AND
  rules:
    - { field: transaction.token.contract, operator: EQUALS, value: TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t }
aggregation:
  window: 1h
  groupBy: [transaction.from]
  function: SUM
  field: transaction.amount
  operator: GREATER_THAN_OR_EQUAL
  threshold: "50000"
severity: HIGH
cooldown: 6h
EOF

curl -s -X POST -H "X-API-Key: $OCI_KEY" -H "Content-Type: application/yaml" \
  --data-binary @burst.yaml localhost:8080/api/v1/rules | jq '{id, enabled}'   # rules are created disabled

curl -s -X POST -H "X-API-Key: $OCI_KEY" -H "Content-Type: application/json" -d '{}' \
  localhost:8080/api/v1/rules/demo-usdt-burst/test | jq '{evaluatedEvents, matchCount, estimatedAlertsPerDay}'
```

DRY_RUN replays the recorded events (in-memory history): no alert is created, no notification is sent. The console offers the same through **Alert Builder** and **Rule Simulator**.

## 5. Enable and receive a webhook

Connector targets must be public `https://` URLs (the SSRF guard rejects `http://`, private and loopback addresses, demo mode included). For a local test, expose a receiver through an HTTPS tunnel and use its URL:

```bash
curl -s -X POST -H "X-API-Key: $OCI_KEY" -H "Content-Type: application/json" \
  -d '{"name":"local-sink","url":"https://YOUR-HTTPS-RECEIVER.example/hook","events":["alert.opened"]}' \
  localhost:8080/api/v1/webhooks | jq   # note the id (wh-...) and the secret, shown once
```

Add `actions: [{type: WEBHOOK, connectorId: <id returned above>}]` to the rule, `PUT` it, then `POST /api/v1/rules/demo-usdt-burst/enable`. When a matching burst happens your receiver gets a signed `alert.opened` request (`X-OCI-Signature`, `X-OCI-Event`, `X-OCI-Delivery-Id`). Verify the signature with the snippets in the [API docs](../api/README.md#webhook-payload-and-signature). `SLACK`, `DISCORD` and `TELEGRAM` connectors are created the same way with `"type"`.

## 6. Triage

In the UI, open **Alert Center**: acknowledge, assign, comment, mark resolved or false positive.

## 7. Stop

```bash
docker compose down        # all application state is in memory in this version and is lost
```
