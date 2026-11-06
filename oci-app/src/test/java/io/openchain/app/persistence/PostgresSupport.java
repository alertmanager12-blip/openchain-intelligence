package io.openchain.app.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL 17 container per test JVM (Testcontainers; removed by Ryuk at exit). Each test gets its own empty
 * database ({@link #newDatabase()}), so tests are independent and the Flyway migration runs on an empty schema.
 */
public final class PostgresSupport {

    public static final String IMAGE = "postgres:17";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static PostgreSQLContainer container;

    private PostgresSupport() {}

    /** Skips the annotated tests, with an explicit reason, when no Docker daemon is reachable. */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @ExtendWith(DockerCondition.class)
    public @interface RequiresDocker {}

    public static final class DockerCondition implements ExecutionCondition {
        @Override public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            boolean ok;
            try {
                ok = DockerClientFactory.instance().isDockerAvailable();
            } catch (RuntimeException | Error e) {
                ok = false;
            }
            if (ok) return ConditionEvaluationResult.enabled("Docker available: PostgreSQL integration tests run");
            System.err.println("[oci] Docker is not available: PostgreSQL (Testcontainers) integration tests are SKIPPED");
            return ConditionEvaluationResult.disabled("Docker is not available: PostgreSQL (Testcontainers) integration tests skipped");
        }
    }

    public record Database(String name, String url, String username, String password) {
        public DataSource dataSource() { return new DriverManagerDataSource(url, username, password); }
    }

    public static synchronized PostgreSQLContainer container() {
        if (container == null) {
            container = new PostgreSQLContainer(IMAGE);
            container.start();
        }
        return container;
    }

    /** A new, empty database in the shared container. */
    public static Database newDatabase() {
        PostgreSQLContainer c = container();
        String name = "oci_test_" + ProcessHandle.current().pid() + "_" + SEQ.incrementAndGet();
        try (Connection conn = DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword())) {
            conn.createStatement().execute("create database " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        String url = "jdbc:postgresql://" + c.getHost() + ":" + c.getMappedPort(5432) + "/" + name;
        return new Database(name, url, c.getUsername(), c.getPassword());
    }

    /** Applies the application's migrations (classpath:db/migration), as Spring Boot does at startup. */
    public static MigrateResult migrate(DataSource ds) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }
}
