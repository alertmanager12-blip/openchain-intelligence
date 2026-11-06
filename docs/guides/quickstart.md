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
apiVersion: oci.rules/v1
kind: Rule
metadata:
  name: demo-usdt-burst
  severity: HIGH
spec:
  networks: [tron]
  eventTypes: [TOKEN_TRANSFER]
  where:
    all:
      - { field: transaction.token.symbol, op: eq, value: USDT }
  window: { type: sliding, size: 1h, groupBy: [transaction.from] }
  having:
    all:
      - { aggregate: count, op: gte, value: 5 }
      - { aggregate: sum, field: transaction.amount, op: gte, value: "50000" }
  dedup: { cooldown: 6h }
EOF

curl -s -X POST -H "Authorization: Bearer $OCI_KEY" -H "Content-Type: application/yaml" \
  -H "Idempotency-Key: $(uuidgen)" --data-binary @burst.yaml localhost:8080/api/v1/rules/import | jq
RULE_ID=...   # from the response

curl -s -X POST -H "Authorization: Bearer $OCI_KEY" -H "Content-Type: application/json" \
  -d '{"network":"tron","from":"-PT2H","to":"now"}' \
  localhost:8080/api/v1/rules/$RULE_ID/test | jq '.stats, .matches[0]'
```

DRY_RUN replays recent simulated transfers: no alert is created, no notification is sent.

## 5. Enable and receive a webhook

Start a local receiver (demo mode allows `http://` sinks on the compose network):

```bash
docker run --rm -d --name sink --network openchain-intelligence_default -p 9999:8080 mendhak/http-https-echo:37
curl -s -X POST -H "Authorization: Bearer $OCI_KEY" -H "Content-Type: application/json" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"name":"local-sink","url":"http://sink:8080/hook","events":["alert.opened","alert.retracted"]}' \
  localhost:8080/api/v1/webhooks | jq   # note the secret, shown once
```

Add `actions: [{connector: local-sink}]` to the rule, `PUT` it, then `POST /api/v1/rules/$RULE_ID/enable`. Within a few simulated minutes `docker logs sink` shows a signed `alert.opened` request. Verify the signature with the snippets in the [API docs](../api/README.md#webhook-payload-and-signature).

## 6. Triage

In the UI, open **Alerts**: acknowledge, assign, comment, mark resolved or false positive. When the simulator plays its reorg scenario, an alert depending on an orphaned block moves to `RETRACTED`.

## 7. Stop

```bash
docker compose down        # keep data
docker compose down -v     # also delete the database volume
```
