package io.openchain.intelligence.label;

import static org.assertj.core.api.Assertions.*;

import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LabelFileLoaderTest {
    @Test void loadsBundledDemoCsv() throws Exception {
        var labels = LabelFileLoader.fromClasspath("labels/demo-labels.csv");
        assertThat(labels).hasSize(5);
        assertThat(labels).allMatch(l -> l.address().value().toLowerCase().contains("demo"));
        var repo = new InMemoryLabelRepository();
        repo.saveAll(labels);
        var sanc = repo.findByAddress(new Address(Network.ETHEREUM, "0xDEMO00000000000000000000000000000000A001"));
        assertThat(sanc).singleElement().satisfies(l -> {
            assertThat(l.category()).isEqualTo(LabelCategory.SANCTIONED);
            assertThat(l.evidenceLevel()).isEqualTo(EvidenceLevel.CONFIRMED_BY_SOURCE);
            assertThat(l.sourceReliability()).isEqualTo(1.0);
        });
        assertThat(repo.findRisky(new Address(Network.ETHEREUM, "0xdemo00000000000000000000000000000000e001"))).isEmpty();
    }

    @Test void loadsJson() {
        String json = """
                [{"network":"tron","address":"TDEMOfake1","category":"scam","source":"demo-feed","reliability":0.3,
                  "observedAt":"2026-02-01T00:00:00Z"}]""";
        var labels = LabelFileLoader.readJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertThat(labels).singleElement().satisfies(l -> {
            assertThat(l.category()).isEqualTo(LabelCategory.SCAM);
            assertThat(l.evidenceLevel()).isEqualTo(EvidenceLevel.RISK_SIGNAL);
            assertThat(l.observedAt()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        });
    }

    @Test void rejectsInvalidRowsWithLineNumber() {
        String csv = "network,address,category,name,source,reliability,observedAt\nethereum,0xdemo1,SANCTIONED,x,src,1.5,2026-01-01T00:00:00Z\n";
        assertThatThrownBy(() -> LabelFileLoader.readCsv(new StringReader(csv)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line 2");
        assertThatThrownBy(() -> LabelFileLoader.readCsv(new StringReader("ethereum,0xdemo1,NOPE,x,src,1,2026-01-01T00:00:00Z")))
                .hasMessageContaining("line 1");
    }

    @Test void newerObservationFromSameSourceReplacesOlder() {
        var repo = new InMemoryLabelRepository();
        var a = new Address(Network.ETHEREUM, "0xdemo1");
        repo.save(new AddressLabel(a, LabelCategory.SCAM, null, "s", 0.3, Instant.parse("2026-01-01T00:00:00Z"), EvidenceLevel.RISK_SIGNAL, null));
        repo.save(new AddressLabel(a, LabelCategory.SCAM, null, "s", 0.6, Instant.parse("2026-02-01T00:00:00Z"), EvidenceLevel.RISK_SIGNAL, null));
        repo.save(new AddressLabel(a, LabelCategory.SCAM, null, "s", 0.1, Instant.parse("2025-01-01T00:00:00Z"), EvidenceLevel.RISK_SIGNAL, null));
        assertThat(repo.findByAddress(a)).singleElement().satisfies(l -> assertThat(l.sourceReliability()).isEqualTo(0.6));
    }
}
