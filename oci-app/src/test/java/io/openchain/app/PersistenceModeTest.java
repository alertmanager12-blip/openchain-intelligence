package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.alert.AlertRepository;
import io.openchain.alerting.alert.InMemoryAlertRepository;
import io.openchain.app.persistence.PersistenceMode;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** Without spring.datasource.url the application runs in memory: no DataSource, no Flyway, in-memory stores. */
class PersistenceModeTest extends ApiTestSupport {

    @Autowired ApplicationContext ctx;

    @Test
    void inMemoryModeWithoutDatasource() {
        assertThat(ctx.getBean(PersistenceMode.class).jdbc()).isFalse();
        assertThat(ctx.getBeanNamesForType(DataSource.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(org.flywaydb.core.Flyway.class)).isEmpty();
        assertThat(ctx.getBean(AlertRepository.class)).isInstanceOf(InMemoryAlertRepository.class);
    }
}
