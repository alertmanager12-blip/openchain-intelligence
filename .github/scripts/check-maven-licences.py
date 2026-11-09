#!/usr/bin/env python3
"""Fails when a Maven runtime dependency has no licence allowed by ADR-0012.

Input: the THIRD-PARTY.txt written by license-maven-plugin aggregate-add-third-party. A dual-licensed artifact passes
when at least one of its licences is allowed (e.g. Logback: EPL-2.0 or LGPL-2.1-only -> EPL-2.0 is allowed).
"""
import re
import sys

# ADR-0012 allowlist for linked/bundled dependencies, as the names Maven POMs actually use.
ALLOWED = [
    r"apache", r"\bmit\b", r"\bbsd\b", r"bsd-[23]-clause", r"\bisc\b", r"\b0bsd\b", r"postgresql",
    r"\bepl[- ]?2\.0\b", r"eclipse public license.*2\.0", r"\bedl 1\.0\b", r"eclipse distribution license",
    r"\bcc0\b", r"public domain", r"\bmpl[- ]?2\.0\b", r"unicode", r"\bzlib\b",
]


def licences(line):
    head = re.match(r"^((?:\([^()]*\)\s*)+)", line)
    return re.findall(r"\(([^()]*)\)", head.group(1)) if head else []


def main(path):
    bad = []
    for raw in open(path, encoding="utf-8"):
        line = raw.strip()
        if not line.startswith("("):
            continue
        lics = licences(line)
        if not any(re.search(p, l, re.IGNORECASE) for l in lics for p in ALLOWED):
            bad.append(line)
    if bad:
        print("Dependencies without an allowed licence (ADR-0012):")
        for b in bad:
            print("  " + b)
        return 1
    print("Maven licence policy: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "target/generated-sources/license/THIRD-PARTY.txt"))
