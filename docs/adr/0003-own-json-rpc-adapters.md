# ADR-0003: Own HTTP/JSON-RPC adapters instead of Web3j or vendor SDKs

- Status: accepted
- Date: 2026-11-06

## Context and problem statement

`oci-blockchain` must talk to TRON (TronGrid-compatible HTTP API or a self-hosted `java-tron` node) and EVM chains (JSON-RPC). We need only a small surface: head/block lookup, transaction and receipt, `eth_getLogs` for `Transfer` events, balances. We need strict control of timeouts, retries, batching, rate limiting, error classification (retryable or not) and exact number parsing.

## Considered options

1. **Own thin adapters** on the JDK `HttpClient` (or Spring `RestClient`) + Jackson, implementing the `BlockchainProvider` SPI.
2. Web3j (Apache-2.0) for EVM + Trident/tron SDK for TRON.
3. Vendor SDKs (Alchemy, Infura, QuickNode, TronGrid clients).

## Decision outcome

Chosen option: **1**.

### Pros

- Minimal dependencies (no OkHttp/RxJava/Bouncy Castle chains pulled in by Web3j) and smaller CVE surface.
- Uniform resilience: one rate limiter, circuit breaker and retry policy for all providers.
- Exact parsing of hex quantities to `BigInteger`; no silent `long` overflow.
- Vendor neutrality: any endpoint speaking the standard protocol works (own node, public RPC, commercial).
- Easy to fake in tests (WireMock / recorded fixtures).

### Cons

- We maintain ABI decoding for the few events we need (`Transfer(address,address,uint256)`, `Approval`, ownership events) ourselves.
- No transaction signing (not needed: OCI is read-only).

## Consequences

- Each adapter has contract tests against recorded JSON fixtures of real responses.
- If we later need broad ABI decoding, we may depend on Web3j's `abi` module only (Apache-2.0), behind the same SPI.

## Pros and cons of the other options

- **Web3j**: complete and well known, but heavy, opinionated I/O stack, and EVM only.
- **Vendor SDKs**: proprietary extensions and lock-in, inconsistent error models, licence variety.
