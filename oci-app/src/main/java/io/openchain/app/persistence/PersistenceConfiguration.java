package io.openchain.app.persistence;

import io.openchain.alerting.alert.AlertRepository;
import io.openchain.alerting.alert.InMemoryAlertRepository;
import io.openchain.alerting.notify.delivery.DeliveryStore;
import io.openchain.alerting.notify.delivery.InMemoryDeliveryStore;
import io.openchain.app.config.OciProperties;
import io.openchain.app.labels.InMemoryLabelReportRepository;
import io.openchain.app.labels.JdbcLabelReportRepository;
import io.openchain.app.labels.LabelReportRepository;
import io.openchain.app.rules.InMemoryRuleRepository;
import io.openchain.app.rules.JdbcRuleRepository;
import io.openchain.app.rules.RuleRepository;
import io.openchain.app.security.ApiKeyRepository;
import io.openchain.app.security.InMemoryApiKeyRepository;
import io.openchain.app.security.InMemoryUserRepository;
import io.openchain.app.security.JdbcApiKeyRepository;
import io.openchain.app.security.JdbcUserRepository;
import io.openchain.app.security.UserRepository;
import io.openchain.app.webhooks.ConnectorRepository;
import io.openchain.app.webhooks.InMemoryConnectorRepository;
import io.openchain.app.webhooks.JdbcConnectorRepository;
import java.net.InetAddress;
import java.time.Clock;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operator-state stores. JDBC (PostgreSQL, schema managed by Flyway at startup) when {@code spring.datasource.url}
 * is set, in memory otherwise; services depend only on the repository interfaces. Chain-derived state is always in
 * memory (see {@link PersistenceMode}).
 */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PersistenceConfiguration.class);

    @Bean PersistenceMode persistenceMode(Environment env) { return new PersistenceMode(PersistenceMode.jdbcConfigured(env)); }

    @Configuration(proxyBeanMethods = false)
    @Conditional(PersistenceMode.InMemory.class)
    static class InMemory {
        @Bean TransactionRunner transactionRunner() { return TransactionRunner.DIRECT; }
        @Bean AlertRepository alertRepository() { return new InMemoryAlertRepository(); }
        @Bean DeliveryStore deliveryStore() { return new InMemoryDeliveryStore(); }
        @Bean ApiKeyRepository apiKeyRepository() { return new InMemoryApiKeyRepository(); }
        @Bean UserRepository userRepository() { return new InMemoryUserRepository(); }
        @Bean ConnectorRepository connectorRepository() { return new InMemoryConnectorRepository(); }
        @Bean RuleRepository ruleRepository() { return new InMemoryRuleRepository(); }
        @Bean LabelReportRepository labelReportRepository() { return new InMemoryLabelReportRepository(); }

        @Bean @Order(Ordered.HIGHEST_PRECEDENCE)
        ApplicationRunner persistenceReport(OciProperties props) {
            return args -> {
                if (props.getDemo().isEnabled()) {
                    log.info("Persistence mode: IN-MEMORY (no spring.datasource.url) - operator state is lost on restart (demo mode)");
                } else {
                    log.warn("Persistence mode: IN-MEMORY (no spring.datasource.url) - API keys, connectors, rules, alerts and "
                            + "deliveries are NOT persistent and are lost on restart. Set SPRING_DATASOURCE_URL for PostgreSQL.");
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(PersistenceMode.Jdbc.class)
    static class Jdbc {

        /** Fails fast (before any connector is loaded) when OCI_CRYPTO_MASTER_KEY is missing or invalid. */
        @Bean SecretCipher secretCipher(OciProperties props) {
            return SecretCipher.fromBase64(props.getSecurity().getCryptoMasterKey(), props.getDemo().isEnabled());
        }

        @Bean TransactionRunner transactionRunner(PlatformTransactionManager tm) {
            TransactionTemplate t = new TransactionTemplate(tm);
            return new TransactionRunner() {
                @Override public <T> T inTransaction(java.util.function.Supplier<T> work) { return t.execute(s -> work.get()); }
            };
        }

        @Bean AlertRepository alertRepository(NamedParameterJdbcTemplate jdbc, TransactionRunner tx) { return new JdbcAlertRepository(jdbc, tx); }

        @Bean DeliveryStore deliveryStore(NamedParameterJdbcTemplate jdbc, OciProperties props, Clock clock) {
            return new JdbcDeliveryStore(jdbc, instanceId(), props.getPersistence().getDeliveryClaimLease(), clock);
        }

        @Bean ApiKeyRepository apiKeyRepository(NamedParameterJdbcTemplate jdbc) { return new JdbcApiKeyRepository(jdbc); }
        @Bean UserRepository userRepository(NamedParameterJdbcTemplate jdbc) { return new JdbcUserRepository(jdbc); }
        @Bean ConnectorRepository connectorRepository(NamedParameterJdbcTemplate jdbc, SecretCipher cipher) { return new JdbcConnectorRepository(jdbc, cipher); }
        @Bean RuleRepository ruleRepository(NamedParameterJdbcTemplate jdbc, TransactionRunner tx) { return new JdbcRuleRepository(jdbc, tx); }
        @Bean LabelReportRepository labelReportRepository(NamedParameterJdbcTemplate jdbc) { return new JdbcLabelReportRepository(jdbc); }

        @Bean @Order(Ordered.HIGHEST_PRECEDENCE)
        ApplicationRunner persistenceReport(ObjectProvider<Flyway> flyway, SecretCipher cipher, Environment env) {
            return args -> report(flyway.getIfAvailable(), cipher, env);
        }

        private static void report(Flyway flyway, SecretCipher cipher, Environment env) {
            String schema = "unknown";
            int applied = 0;
            if (flyway != null) {
                MigrationInfoService info = flyway.info();
                MigrationInfo current = info.current();
                schema = current == null ? "empty" : "v" + current.getVersion();
                applied = info.applied().length;
            }
            log.info("Persistence mode: POSTGRESQL ({}) - operator state is persistent; schema {} ({} migration(s) applied), "
                    + "secrets encrypted with master key {}; chain-derived state stays in memory and is rebuilt by re-indexing",
                    redactUrl(env.getProperty(PersistenceMode.URL_PROPERTY)), schema, applied, cipher.keyId());
        }

        static String redactUrl(String url) {
            if (url == null) return "";
            int q = url.indexOf('?');
            return q < 0 ? url : url.substring(0, q) + "?…"; // query parameters may carry a password
        }

        private static String instanceId() {
            String host;
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "unknown";
            }
            return host + ":" + ProcessHandle.current().pid() + ":" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
}
