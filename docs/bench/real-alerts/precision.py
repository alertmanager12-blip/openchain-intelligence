"""Precision per rule from a labelled grid (TP / FP / ?), with a 95 % Wilson interval. '?' is counted apart, never as TP."""
import csv, math, sys
from collections import defaultdict


def wilson(k, n, z=1.96):
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0.0, c - h), min(1.0, c + h))


def main(path):
    by = defaultdict(lambda: {"TP": 0, "FP": 0, "?": 0, "blank": 0})
    with open(path, newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            v = (r["verdict (TP/FP/?)"] or "").strip().upper()
            by[r["rule"]][v if v in ("TP", "FP", "?") else "blank"] += 1
    print(f"{'rule':30} {'TP':>3} {'FP':>3} {'?':>3} {'blank':>5}  precision  95% interval")
    tot = {"TP": 0, "FP": 0}
    for rule, c in sorted(by.items()):
        n = c["TP"] + c["FP"]
        tot["TP"] += c["TP"]; tot["FP"] += c["FP"]
        lo, hi = wilson(c["TP"], n)
        p = f"{c['TP'] / n:.2f}" if n else "  - "
        print(f"{rule:30} {c['TP']:>3} {c['FP']:>3} {c['?']:>3} {c['blank']:>5}  {p:>9}  [{lo:.2f}, {hi:.2f}]" if n else f"{rule:30} {c['TP']:>3} {c['FP']:>3} {c['?']:>3} {c['blank']:>5}       -")
    n = tot["TP"] + tot["FP"]
    if n:
        lo, hi = wilson(tot["TP"], n)
        print(f"{'ALL (judged)':30} {tot['TP']:>3} {tot['FP']:>3}  precision {tot['TP'] / n:.2f} [{lo:.2f}, {hi:.2f}]  (sample per rule, not weighted by volume)")


if __name__ == "__main__":
    main(sys.argv[1])
