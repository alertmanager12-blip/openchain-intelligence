package io.openchain.intelligence.snapshot;

import static io.openchain.intelligence.Fx.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.risk.ScoringConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ADR-0024 step 3d: the working set written to a snapshot and installed into a fresh pipeline is the same working set,
 * and the two pipelines then give the same results for the same new events (detection, scores, profiles).
 */
class WorkingSetSnapshotTest {
    final Clock clock = Clock.fixed(days(2), ZoneOffset.UTC);
    final Address sanctioned = a("5a5a000000000000000000000000000000000001");

    IntelligencePipeline pipeline() {
        IntelligencePipeline p = IntelligencePipeline.inMemory(DetectionConfig.defaults(), ScoringConfig.defaults(), clock);
        p.labels().save(new AddressLabel(sanctioned, LabelCategory.SANCTIONED, "DEMO sanctioned", "demo-sanctions", 1.0, T0,
                EvidenceLevel.CONFIRMED_BY_SOURCE, "demo://s"));
        return p;
    }

    static Address w(int i) { return a(String.format("%040d", i)); }

    /** Exposure, fan-out, repeated counterparties, two tokens, native-free: signals and scores are produced. */
    static void history(IntelligencePipeline p, Address sanctioned) {
        int log = 0;
        for (int i = 0; i < 40; i++) {
            Address from = i % 7 == 0 ? sanctioned : w(i % 9);
            p.onTransfer(transfer("0xh" + i, log++, from, w(100 + i % 5), (i * 137 + 11) + ".5", i % 2 == 0 ? USDT : DAI, min(i * 3L)));
        }
        for (int i = 0; i < 25; i++) p.onTransfer(transfer("0xfan", log++, w(100), w(200 + i), "1200", USDT, min(150)));
    }

    static byte[] snapshot(IntelligencePipeline p) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        WorkingSetSnapshot.write(new DataOutputStream(b), p,
                new WorkingSetSnapshot.Header("test", Instant.EPOCH, Map.of(Network.ETHEREUM, 123L)));
        return b.toByteArray();
    }

    static WorkingSetSnapshot.Contents read(byte[] b) throws IOException {
        return WorkingSetSnapshot.read(new DataInputStream(new ByteArrayInputStream(b)));
    }

    static Set<Address> addresses(IntelligencePipeline p) {
        Set<Address> s = new LinkedHashSet<>();
        for (ValueFlow f : p.flows().all()) { s.add(f.from()); s.add(f.to()); }
        return s;
    }

    static void assertSameWorkingSet(IntelligencePipeline a, IntelligencePipeline b) {
        assertThat(Set.copyOf(b.flows().all())).isEqualTo(Set.copyOf(a.flows().all()));
        for (Address x : addresses(a)) {
            assertThat(b.flows().incoming(x)).isEqualTo(a.flows().incoming(x));
            assertThat(b.flows().outgoing(x)).isEqualTo(a.flows().outgoing(x));
            assertThat(b.profiles().profile(x)).isEqualTo(a.profiles().profile(x));
        }
        assertThat(b.profiles().size()).isEqualTo(a.profiles().size());
        assertThat(b.signals().findAll()).isEqualTo(a.signals().findAll());
        assertThat(b.scoring().repository().all()).isEqualTo(a.scoring().repository().all());
        assertThat(b.balances().entries()).isEqualTo(a.balances().entries());
        assertThat(b.balances().authoritativeNetworks()).isEqualTo(a.balances().authoritativeNetworks());
    }

    @Test void aRestoredWorkingSetIsIdenticalAndBehavesTheSame() throws IOException {
        IntelligencePipeline before = pipeline();
        history(before, sanctioned);
        before.balances().markAuthoritative(Network.TRON);
        before.balances().recordSenderBalanceBefore("ethereum:0xh1:1", new BigDecimal("1234.000500"));
        assertThat(before.signals().size()).as("the scenario produces signals").isPositive();

        byte[] b = snapshot(before);
        WorkingSetSnapshot.Contents c = read(b);
        assertThat(c.header().watermarks()).containsEntry(Network.ETHEREUM, 123L);
        IntelligencePipeline after = pipeline();
        WorkingSetSnapshot.Counts n = WorkingSetSnapshot.install(c, after);
        assertThat(n.flows()).isEqualTo(before.flows().size());
        assertSameWorkingSet(before, after);
        assertThat(snapshot(after)).isEqualTo(b); // and the same snapshot again

        // the same new events after the restart give the same results
        List<Object> r1 = new ArrayList<>(), r2 = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            var t = transfer("0xn" + i, i, i % 5 == 0 ? sanctioned : w(100 + i % 5), w(300 + i % 4), "2500", USDT, min(200 + i));
            r1.add(before.onTransfer(t));
            r2.add(after.onTransfer(t));
        }
        var replay = transfer("0xh3", 3, w(3), w(103), "422.5", DAI, min(9)); // already applied before the snapshot
        r1.add(before.onTransfer(replay));
        r2.add(after.onTransfer(replay));
        assertThat(r2).isEqualTo(r1);
        assertSameWorkingSet(before, after);
    }

    @Test void flowsShareTheAddressInstancesOfTheirProfiles() throws IOException {
        IntelligencePipeline before = pipeline();
        history(before, sanctioned);
        IntelligencePipeline after = pipeline();
        WorkingSetSnapshot.install(read(snapshot(before)), after);
        for (ValueFlow f : after.flows().all()) {
            assertThat(f.from()).isSameAs(after.profiles().canonicalAddress(f.from()));
            assertThat(f.to()).isSameAs(after.profiles().canonicalAddress(f.to()));
        }
    }

    @Test void aCorruptedOrForeignSnapshotIsRefusedAndNothingIsInstalled() throws IOException {
        IntelligencePipeline before = pipeline();
        history(before, sanctioned);
        byte[] b = snapshot(before);
        byte[] cut = java.util.Arrays.copyOf(b, b.length - 1);
        assertThatThrownBy(() -> read(cut)).isInstanceOfAny(java.io.EOFException.class, SnapshotIO.SnapshotFormatException.class);
        byte[] foreign = b.clone();
        foreign[0] = 0;
        assertThatThrownBy(() -> read(foreign)).isInstanceOf(SnapshotIO.SnapshotFormatException.class).hasMessageContaining("not a working-set");
        byte[] future = b.clone();
        future[4] = (byte) (WorkingSetSnapshot.FORMAT + 1);
        assertThatThrownBy(() -> read(future)).isInstanceOf(SnapshotIO.SnapshotFormatException.class).hasMessageContaining("format version");
    }

    @Test void installingIntoANonEmptyWorkingSetIsRefused() throws IOException {
        IntelligencePipeline before = pipeline();
        history(before, sanctioned);
        WorkingSetSnapshot.Contents c = read(snapshot(before));
        assertThatThrownBy(() -> WorkingSetSnapshot.install(c, before)).isInstanceOf(IllegalStateException.class);
    }
}
