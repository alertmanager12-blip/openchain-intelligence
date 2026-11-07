package io.openchain.app.labels;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The catalog is the repository the application uses: it must report "nothing risky" so exposure detection is skipped. */
class LabelCatalogRiskyTest {
    @Test void reportsRiskyLabelsThroughTheDelegate() {
        LabelCatalog catalog = new LabelCatalog();
        assertThat(catalog.hasRisky()).isFalse();
        Address a = new Address(Network.ETHEREUM, "0x" + "1".repeat(40));
        catalog.save(new AddressLabel(a, LabelCategory.EXCHANGE, "Some exchange", "test", 0.9, Instant.EPOCH, EvidenceLevel.RISK_SIGNAL, null));
        assertThat(catalog.hasRisky()).isFalse(); // an exchange is not a risky category
        catalog.save(new AddressLabel(a, LabelCategory.SANCTIONED, "Listed", "test", 1.0, Instant.EPOCH, EvidenceLevel.CONFIRMED_BY_SOURCE, null));
        assertThat(catalog.hasRisky()).isTrue();
        catalog.remove(a, LabelCategory.SANCTIONED, "test");
        assertThat(catalog.hasRisky()).isFalse();
    }
}
