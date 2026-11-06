package io.openchain.app.persistence;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Where operator state (API keys, connectors, rules, alerts, deliveries, label reports, users) lives:
 * PostgreSQL (JDBC + Flyway) when {@code spring.datasource.url} is set, in memory otherwise.
 * Chain-derived state (flows, wallet profiles, signals, risk scores, indexer checkpoints, event history) is always in
 * memory and rebuilt by re-indexing from the configured start block. TODO(persistence): chain-derived state.
 */
public record PersistenceMode(boolean jdbc) {

    public static final String URL_PROPERTY = "spring.datasource.url";

    public static boolean jdbcConfigured(Environment env) {
        String url = env.getProperty(URL_PROPERTY);
        return url != null && !url.isBlank();
    }

    public String label() { return jdbc ? "postgresql" : "in-memory"; }

    /** Matches when {@code spring.datasource.url} is set (non-blank). */
    public static final class Jdbc implements Condition {
        @Override public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata md) { return jdbcConfigured(ctx.getEnvironment()); }
    }

    /** Matches when {@code spring.datasource.url} is absent or blank. */
    public static final class InMemory implements Condition {
        @Override public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata md) { return !jdbcConfigured(ctx.getEnvironment()); }
    }
}
