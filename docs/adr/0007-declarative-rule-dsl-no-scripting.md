# ADR-0007: Declarative rule DSL (YAML/JSON, schema v1), no scripting

- Status: accepted
- Date: 2026-11-06

## Context and problem statement

Users must define monitoring rules (filters, thresholds, sliding windows, grouping, dedup, cooldown, notification targets). Rules are authored by tenants in a multi-tenant system and evaluated on every event, so they are untrusted input running on a hot path.

## Considered options

1. **Declarative DSL** in YAML/JSON validated by JSON Schema `oci.rules/v1`, compiled to a closed operator tree.
2. Expression language (SpEL, MVEL, JEXL, CEL).
3. Embedded scripting (GraalJS, Groovy, Lua).
4. WASM plugins.
5. SQL-like streaming language (e.g. Flink SQL / ksqlDB style).

## Decision outcome

Chosen option: **1**. See [rule-engine.md](../architecture/rule-engine.md) for the schema, operators and limits.

### Pros

- No code execution: removes the RCE/sandbox-escape class entirely (SpEL/OGNL injection is a recurring CVE class).
- Statically bounded cost (node count, depth, window size, set sizes).
- Explainable alerts: each node renders a human explanation with observed values.
- Portable: the tree can be translated to SQL (ClickHouse) or Flink later.
- Git-friendly, reviewable, shareable rule packs.

### Cons

- Less expressive; new needs require new operators in Java and a schema minor version.
- YAML pitfalls (types, anchors) — mitigated by a safe loader, string decimals and schema validation.

## Consequences

- No regex in v1 (ReDoS); a linear-time regex engine (RE2/J) may be added later with length limits.
- CEL (Apache-2.0, non-Turing-complete) is the only expression language we would consider in the future, as an opt-in, budgeted extension; not in v1.

## Pros and cons of the other options

- **SpEL/MVEL/JEXL**: powerful, but designed for trusted input; sandboxing is fragile.
- **Scripting**: maximal flexibility, unbounded cost, sandbox escapes, poor explainability.
- **WASM**: strong isolation, but heavy toolchain for users and opaque logic.
- **Streaming SQL**: expressive, but requires a streaming engine and is harder to validate/limit per tenant.
