package io.openchain.app.persistence;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * In-memory mode (no {@code spring.datasource.url}): excludes the DataSource and Flyway auto-configurations, which
 * would otherwise fail ("no embedded database") because the JDBC starter and the PostgreSQL driver are on the
 * classpath. Runs after the configuration files are loaded. Registered in {@code META-INF/spring.factories}.
 */
public class PersistenceEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String EXCLUDE = "spring.autoconfigure.exclude";
    static final List<String> JDBC_AUTO_CONFIGURATIONS = List.of(
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (PersistenceMode.jdbcConfigured(env)) return;
        Set<String> excluded = new LinkedHashSet<>();
        String existing = env.getProperty(EXCLUDE);
        if (existing != null) for (String s : existing.split(",")) if (!s.isBlank()) excluded.add(s.trim());
        excluded.addAll(JDBC_AUTO_CONFIGURATIONS);
        env.getPropertySources().addFirst(new MapPropertySource("oci-in-memory-persistence",
                Map.of(EXCLUDE, String.join(",", excluded))));
    }

    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
