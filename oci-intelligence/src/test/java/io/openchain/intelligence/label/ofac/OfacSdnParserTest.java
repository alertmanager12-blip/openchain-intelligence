package io.openchain.intelligence.label.ofac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-0027: the OFAC SDN advanced XML read into sanctioned addresses of the indexed networks, with their provenance. */
class OfacSdnParserTest {

    SdnPublication parse() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/ofac/sdn-advanced-extract.xml")) {
            return OfacSdnParser.parse(in);
        }
    }

    @Test void readsThePublicationDate() throws Exception {
        assertThat(parse().issued()).isEqualTo(LocalDate.of(2026, 11, 18));
    }

    @Test void keepsOnlyAddressesOfIndexedNetworksUnderTheirListedCurrency() throws Exception {
        assertThat(parse().addresses()).extracting(SdnAddress::address).containsExactlyInAnyOrder(
                new Address(Network.TRON, "TNiq9AXBp9EjUqhDhrwrfvAA8U3GUQZH81"),
                new Address(Network.ETHEREUM, "0xabcdef0123456789abcdef0123456789abcdef01"),
                new Address(Network.ETHEREUM, "0x2222222222222222222222222222222222222222"));
        // 0x1111… is listed only under BSC: same key format, but OFAC named another chain; XBT and malformed values are left out
    }

    @Test void oneAddressListedUnderTwoCurrenciesIsOneEntryWithBoth() throws Exception {
        SdnAddress t = parse().addresses().stream().filter(a -> a.address().network() == Network.TRON).findFirst().orElseThrow();
        assertThat(t.currencies()).containsExactly("TRX", "USDT");
        assertThat(t.sdnUid()).isEqualTo("4632");
        assertThat(t.name()).as("the primary name, not an a.k.a.").isEqualTo("EXAMPLE EXCHANGE LTD");
        assertThat(t.programs()).containsExactly("IRAN", "SDGT");
    }

    @Test void anIndividualsNameJoinsItsParts() throws Exception {
        SdnAddress u = parse().addresses().stream().filter(a -> a.address().value().startsWith("0x2222")).findFirst().orElseThrow();
        assertThat(u.name()).isEqualTo("Ivan PETROV");
        assertThat(u.programs()).containsExactly("CYBER2");
    }

    @Test void becomesConfirmedSanctionsLabelsDatedByThePublication() throws Exception {
        List<AddressLabel> labels = parse().toLabels();
        AddressLabel t = labels.stream().filter(l -> l.address().network() == Network.TRON).findFirst().orElseThrow();
        assertThat(t.category()).isEqualTo(LabelCategory.SANCTIONED);
        assertThat(t.source()).isEqualTo("OFAC SDN");
        assertThat(t.evidenceLevel()).isEqualTo(EvidenceLevel.CONFIRMED_BY_SOURCE);
        assertThat(t.sourceReliability()).isEqualTo(1.0);
        assertThat(t.observedAt()).isEqualTo(Instant.parse("2026-11-18T00:00:00Z"));
        assertThat(t.name()).isEqualTo("EXAMPLE EXCHANGE LTD");
        assertThat(t.reference()).isEqualTo("OFAC SDN uid 4632 (IRAN, SDGT; listed under TRX, USDT), publication of 2026-11-18");
    }

    @Test void aDocumentThatIsNotTheSdnListIsRefused() {
        assertThatThrownBy(() -> OfacSdnParser.parse(new ByteArrayInputStream("<html><body>error</body></html>".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not an OFAC");
    }
}
