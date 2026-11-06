# ADR-0008: Explainable risk scoring — saturating aggregation, decay, no double counting

- Status: accepted
- Date: 2026-11-06

## Context and problem statement

A wallet risk score (0–100) summarises many signals. It influences human decisions with potential legal and financial impact for the address owner. Opaque ML scores are hard to justify, and naive additive scores overshoot when correlated signals repeat.

## Considered options

1. **Rule-based explainable aggregation** with saturation, confidence weighting, time decay and deduplication; each point traceable to a `RiskScore.Contribution`.
2. Supervised ML model (gradient boosting) with SHAP explanations.
3. Simple weighted sum, capped at 100.
4. Maximum of signal severities.

## Decision outcome

Chosen option: **1**.

For each factor *f* (signal type or label category) with weight *wᶠ* (0–100):

```
pᶠ = wᶠ × confidence × decay(age)                decay(age) = 0.5 ^ (age / halfLifeᶠ)
score = 100 × (1 − Π (1 − pᶠ / 100))            (saturating "noisy-OR" aggregation)
```

- **No double counting**: within a factor, only the strongest evidence counts (`max`), not the sum; the same underlying transfer (`eventId`) cannot feed two exposure factors of the same family; direct exposure supersedes indirect exposure to the same labelled counterparty.
- **Evidence levels** are preserved per contribution: `ANOMALY` factors have low default weights and cannot alone push a score above `MEDIUM`; only `CONFIRMED_BY_SOURCE` factors can reach `CRITICAL`.
- **Banding** via `RiskLevel.fromScore` (LOW < 35 ≤ MEDIUM < 65 ≤ HIGH < 85 ≤ CRITICAL); `UNKNOWN` when data is insufficient, never for a 0 score.
- Every score records `method`, `modelVersion` and the `contributions` list (factor, signalId, source, weight, confidence, ageSeconds, points, explanation).

### Pros

- Each point is explained; analysts can contest a specific contribution.
- Saturation keeps the score bounded and monotonic; decay lets old behaviour fade.
- Deterministic and testable.

### Cons

- Weights are expert-tuned, not learned; requires calibration with labelled feedback (false positives).

## Consequences

- Wording policy: the UI and API say "risk signal", "exposure", "anomaly" — never "criminal", "fraudster", "illicit wallet".
- Scores are recomputed on new signals and on retraction (reorg); history is kept in `risk_scores`.
- ML may be added later as an additional *factor* with its own explanation, never as an opaque replacement.
