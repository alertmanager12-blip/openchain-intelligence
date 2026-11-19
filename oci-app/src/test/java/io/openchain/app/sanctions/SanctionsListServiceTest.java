package io.openchain.app.sanctions;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.app.labels.LabelCatalog;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.intelligence.label.LabelCategory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ADR-0027: fetch, guard, keep the last accepted publication, apply it as labels, say its state. */
class SanctionsListServiceTest {
    static final Address TRON = new Address(Network.TRON, "TNiq9AXBp9EjUqhDhrwrfvAA8U3GUQZH81");
    static final Address ETH = new Address(Network.ETHEREUM, "0xabcdef0123456789abcdef0123456789abcdef01");
    static final Address ETH2 = new Address(Network.ETHEREUM, "0x2222222222222222222222222222222222222222");

    static String full;
    final AtomicReference<Object> next = new AtomicReference<>();
    final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T22:00:00Z"));
    InMemorySanctionsListStore store;
    LabelCatalog labels;

    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach void setUp() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/ofac/sdn-advanced-extract.xml")) {
            full = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        store = new InMemorySanctionsListStore();
        labels = new LabelCatalog();
    }

    SanctionsListService service(double maxDrop) {
        return new SanctionsListService(store, labels, () -> {
            Object o = next.get();
            if (o instanceof RuntimeException e) throw e;
            return new ByteArrayInputStream(((String) o).getBytes(StandardCharsets.UTF_8));
        }, clock, maxDrop, java.time.Duration.ofHours(72));
    }

    static boolean sanctioned(LabelCatalog l, Address a) {
        return l.findByAddress(a).stream().anyMatch(x -> x.category() == LabelCategory.SANCTIONED && x.source().equals("OFAC SDN"));
    }

    /** The extract without the feature holding {@code value}. */
    static String without(String value) {
        int i = full.indexOf(value), s = full.lastIndexOf("<Feature ", i), e = full.indexOf("</Feature>", i) + "</Feature>".length();
        return full.substring(0, s) + full.substring(e);
    }

    @Test void appliesAnAcceptedPublicationAsLabelsAndSaysItsState() {
        next.set(full);
        var s = service(0.2);
        assertThat(s.refresh()).isEqualTo(SanctionsListService.Result.ACCEPTED);
        assertThat(sanctioned(labels, TRON)).isTrue();
        assertThat(sanctioned(labels, ETH)).isTrue();
        var st = s.status();
        assertThat(st.state()).isEqualTo(SanctionsListService.State.CURRENT);
        assertThat(st.issued()).hasToString("2026-11-18");
        assertThat(st.tron()).isEqualTo(1);
        assertThat(st.ethereum()).isEqualTo(2);
        assertThat(st.lastError()).isNull();
    }

    @Test void theSamePublicationAgainIsOnlyAFreshCheck() {
        next.set(full);
        var s = service(0.2);
        s.refresh();
        clock.now = clock.now.plusSeconds(86_400);
        assertThat(s.refresh()).isEqualTo(SanctionsListService.Result.UNCHANGED);
        assertThat(store.accepted()).hasSize(1);
        assertThat(s.status().checkedAt()).isEqualTo(clock.now);
    }

    @Test void aSuddenDropIsRefusedAndThePreviousListStaysInForce() {
        next.set(full);
        var s = service(0.2);
        s.refresh();
        next.set(without("0x2222222222222222222222222222222222222222")); // 3 -> 2 addresses: -33 %
        assertThat(s.refresh()).isEqualTo(SanctionsListService.Result.REFUSED);
        assertThat(sanctioned(labels, ETH2)).as("previous list kept").isTrue();
        assertThat(s.status().lastError()).contains("drop");
        assertThat(store.refusedCount()).isEqualTo(1);
    }

    @Test void aDelistedAddressLosesItsLabelFromThatPublicationOn() {
        next.set(full);
        var s = service(0.5);
        s.refresh();
        next.set(without("0x2222222222222222222222222222222222222222").replace("<Day>18</Day>", "<Day>19</Day>"));
        assertThat(s.refresh()).isEqualTo(SanctionsListService.Result.ACCEPTED);
        assertThat(sanctioned(labels, ETH2)).isFalse();
        assertThat(sanctioned(labels, TRON)).isTrue();
        assertThat(s.status().issued()).hasToString("2026-11-19");
    }

    @Test void aFailedDownloadKeepsTheListAndSaysWhy() {
        next.set(full);
        var s = service(0.2);
        s.refresh();
        next.set(new UncheckedIOException(new IOException("HTTP 503")));
        assertThat(s.refresh()).isEqualTo(SanctionsListService.Result.FAILED);
        assertThat(sanctioned(labels, TRON)).isTrue();
        assertThat(s.status().lastError()).contains("HTTP 503");
        assertThat(s.status().state()).isEqualTo(SanctionsListService.State.CURRENT);
    }

    @Test void anUnreadableDocumentIsRefused() {
        next.set("<html>maintenance</html>");
        var s = service(0.2);
        assertThat(s.refresh()).isEqualTo(SanctionsListService.Result.REFUSED);
        assertThat(s.status().state()).isEqualTo(SanctionsListService.State.NONE);
        assertThat(labels.size()).isZero();
    }

    /** Like an HTTP response body: once closed, any read fails with "closed". */
    static final class ClosingStream extends java.io.FilterInputStream {
        boolean closed;
        ClosingStream(byte[] b) { super(new ByteArrayInputStream(b)); }
        @Override public int read() throws IOException { if (closed) throw new IOException("closed"); return super.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { if (closed) throw new IOException("closed"); return super.read(b, off, len); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }

    @Test void aSourceThatRefusesReadsOnceClosedIsStillAcceptedLikeAnHttpBody() {
        // the XML parser closes its input at the end of the document; the digest must not read the source again
        var s = new SanctionsListService(store, labels, () -> new ClosingStream(full.getBytes(StandardCharsets.UTF_8)), clock, 0.2,
                java.time.Duration.ofHours(72));
        assertThat(s.refresh()).isEqualTo(SanctionsListService.Result.ACCEPTED);
        assertThat(sanctioned(labels, TRON)).isTrue();
    }

    @Test void aRestartWithoutNetworkReappliesTheStoredListAndItTurnsStaleAfter72Hours() {
        next.set(full);
        service(0.2).refresh();
        LabelCatalog fresh = new LabelCatalog();
        labels = fresh;
        var restarted = service(0.2);
        restarted.loadStored();
        assertThat(sanctioned(fresh, TRON)).isTrue();
        assertThat(restarted.status().state()).isEqualTo(SanctionsListService.State.CURRENT);
        clock.now = clock.now.plusSeconds(73 * 3600);
        assertThat(restarted.status().state()).isEqualTo(SanctionsListService.State.STALE);
    }
}
