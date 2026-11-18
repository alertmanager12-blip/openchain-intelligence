"""Draw a reproducible, per-rule sample of production alerts into a CSV a person labels (TP / FP / ?).

Usage: docker compose exec -T postgres psql -U oci -d oci -At -c "<SQL printed by --sql>" | python3 sample_alerts.py > grid.csv
The SQL uses setseed so the same database gives the same sample.
"""
import csv, json, sys

QUOTAS = {"wallet-draining": 25, "large-usdt-transfer": 20, "fund-splitting": 12,
          "issuer-freeze-counterparty": 3, "sanctioned-exposure-signal": 3}

SQL = """select setseed(0.2026);
select json_build_object('id', id, 'rule', rule_id, 'severity', severity, 'network', network, 'first', first_seen,
  'last', last_seen, 'n', occurrence_count, 'subject', subject_address, 'cp', subject_counterparty, 'tx', subject_tx_hash,
  'f', subject_fields)
from (select a.*, row_number() over (partition by rule_id order by random()) r from alerts a) s
where (rule_id, r) in (""" + ", ".join(f"('{k}', {i})" for k, q in QUOTAS.items() for i in range(1, q + 1)) + ") order by rule_id, r;"

TX = {"ethereum": "https://etherscan.io/tx/", "tron": "https://tronscan.org/#/transaction/"}
AD = {"ethereum": "https://etherscan.io/address/", "tron": "https://tronscan.org/#/address/"}


def v(f, k):
    x = f.get(k)
    return x.get("v") if isinstance(x, dict) else (x if x is not None else "")


def main():
    if "--sql" in sys.argv:
        print(SQL)
        return
    w = csv.writer(sys.stdout)
    w.writerow(["n", "verdict (TP/FP/?)", "note", "rule", "severity", "chain", "first_seen", "occurrences", "token", "amount",
                "wallet_balance", "subject", "counterparty", "tx_link", "subject_link", "counterparty_link", "alert_id"])
    for i, line in enumerate((l for l in sys.stdin if l.startswith("{")), 1):
        a = json.loads(line)
        f, net = a["f"] or {}, a["network"]
        w.writerow([i, "", "", a["rule"], a["severity"], net, a["first"][:19], a["n"], f.get("transaction.token.symbol", ""),
                    v(f, "transaction.amount"), v(f, "wallet.balance"), a["subject"] or "", a["cp"] or "",
                    TX.get(net, "") + (a["tx"] or ""), AD.get(net, "") + (a["subject"] or ""),
                    AD.get(net, "") + (a["cp"] or "") if a["cp"] else "", a["id"]])


if __name__ == "__main__":
    main()
