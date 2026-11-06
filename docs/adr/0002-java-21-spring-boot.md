# ADR-0002: Java 21 and Spring Boot

- Status: accepted
- Date: 2026-11-06

## Context and problem statement

We need a runtime with strong typing for exact arithmetic (`BigInteger`/`BigDecimal`), mature HTTP/JSON, security (OAuth2 resource server), persistence, observability and a large contributor pool in the compliance/fintech space.

## Considered options

1. **Java 21 (LTS) + Spring Boot** (4.x line).
2. Go.
3. Kotlin + Ktor.
4. Rust.
5. TypeScript/Node.js.

## Decision outcome

Chosen option: **1**, Java release 21 with the Spring Boot 4.1 parent. `oci-core` stays framework-free (JDK only) so the domain model and SPI can be reused without Spring.

### Pros

- Records, sealed types, pattern matching and virtual threads (high-concurrency I/O against chain providers without reactive complexity).
- `BigInteger`/`BigDecimal` in the standard library for uint256 and exact decimals.
- Spring Security OAuth2 resource server, Actuator + Micrometer (Prometheus, OpenTelemetry), Flyway, Testcontainers integration.
- Largest hiring/contributor pool among enterprise compliance users.

### Cons

- Higher memory footprint than Go/Rust (≈ 300–500 MB heap typical).
- Spring Boot major upgrades require migration effort.

## Consequences

- Minimum JDK 21 to build and run; container base `eclipse-temurin:21-jre`.
- Framework annotations are confined to `oci-app` and to adapters; domain code is plain Java.

## Pros and cons of the other options

- **Go**: small binaries and memory, good concurrency, but weaker exact-decimal ecosystem and fewer enterprise security integrations out of the box.
- **Kotlin/Ktor**: concise, but smaller ecosystem for security/persistence glue; Kotlin remains possible later in adapters.
- **Rust**: performance and safety, but slower development and a smaller contributor pool for this audience.
- **Node.js**: shared language with frontend, but number handling (`bigint` interop, JSON numbers) and CPU-bound evaluation are weaker.
