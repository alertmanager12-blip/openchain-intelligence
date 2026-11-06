# Quickstart (demo mode)

Demo mode runs OCI against a **simulated chain**: a deterministic generator produces TRON-like blocks with USDT-TRC20 transfers, including scripted scenarios (burst outflows, fast forwarding, exposure to a demo "sanctioned" address, a 2-block reorg). No external provider, API key or internet access is needed. All demo addresses and labels are synthetic.

> The demo scenario names, addresses and log lines below describe the reference demo dataset; if they differ in your build, list addresses with `GET /api/v1/alerts` or the UI dashboard.

## 1. Start

```bash
docker compose up -d --build
curl -s localhost:8080/actuator/health
# {"status":"UP"}
```

Open http://localhost:4200. In demo mode you are logged in as `demo-analyst` of tenant `demo`.

API calls use the demo key printed in the app logs at start-up:

```bash
docker compose logs app | grep "demo api key"
export OCI_KEY=oci_demo_...      # copy from logs
```

## 2. Check networks

```bash
curl -s -H "Authorization: Bearer $OCI_KEY" localhost:8080/api/v1/networks
```

The `tron` network is backed by the simulator (`providerId: "demo-sim"`), head advances every 3 s.

## 3. Analyse a wallet

The simulator always includes `TDemoBurstOutflow00000000000000001` (a wallet that receives funds from the demo sanctioned address and forwards them quickly).

```bash
curl -s -H "Authorization: Bearer $OCI_KEY" \
  "localhost:8080/api/v1/wallets/TDemoBurstOutflow00000000000000001/analysis?network=tron" | jq
```

Look at `riskScore.contributions[]`: each point is attributed to a signal with evidence (`txHash`, source `demo-sanctions`, `evidenceLevel`).

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
    - { field: transaction.token.symbol, operator: EQUALS, value: USDT }
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

curl -s -X POST -H "Authorization: Bearer $OCI_KEY" -H "Content-Type: application/yaml" \
  -H "Idempotency-Key: $(uuidgen)" --data-binary @burst.yaml localhost:8080/api/v1/rules/import | jq
RULE_ID=demo-usdt-burst   # the rule id (rules are created disabled)

curl -s -X POST -H "Authorization: Bearer $OCI_KEY" -H "Content-Type: application/json" \
  -d "{\"network\":\"tron\",\"from\":\"$(date -u -v-2H +%Y-%m-%dT%H:%M:%SZ)\",\"to\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" \
  localhost:8080/api/v1/rules/$RULE_ID/test | jq '{evaluatedEvents, matchCount, estimatedAlertsPerDay, warnings}, .matches[0]'
```

DRY_RUN replays recent simulated transfers: no alert is created, no notification is sent.

## 5. Enable and receive a webhook

Webhook targets must be public `https://` URLs (the SSRF guard rejects `http://`, private and loopback addresses, demo mode included). For a local test, expose a receiver through an HTTPS tunnel (e.g. a request-bin service) and use its URL:

```bash
curl -s -X POST -H "Authorization: Bearer $OCI_KEY" -H "Content-Type: application/json" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"name":"local-sink","url":"https://YOUR-HTTPS-RECEIVER.example/hook","events":["alert.opened","alert.retracted"]}' \
  localhost:8080/api/v1/webhooks | jq   # note the secret, shown once
```

Add `actions: [{type: WEBHOOK, connectorId: <id returned above, e.g. wh-1a2b...>}]` to the rule, `PUT` it, then `POST /api/v1/rules/$RULE_ID/enable`. Within a few simulated minutes your receiver gets a signed `alert.opened` request (`X-OCI-Signature`, `X-OCI-Event`, `X-OCI-Delivery-Id`). Verify the signature with the snippets in the [API docs](../api/README.md#webhook-payload-and-signature).

## 6. Triage

In the UI, open **Alerts**: acknowledge, assign, comment, mark resolved or false positive. When the simulator plays its reorg scenario, an alert depending on an orphaned block moves to `RETRACTED`.

## 7. Stop

```bash
docker compose down        # keep data
docker compose down -v     # also delete the database volume
```
