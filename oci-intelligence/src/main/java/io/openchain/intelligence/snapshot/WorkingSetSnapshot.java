package io.openchain.intelligence.snapshot;

import static io.openchain.intelligence.snapshot.SnapshotIO.*;

import io.openchain.core.detection.Signal;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.risk.RiskScore;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.profile.WalletProfileAccumulator;
import io.openchain.intelligence.profile.WalletProfileService;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Snapshot of the bounded intelligence working set (ADR-0024 step 3d, docs/PERSISTENCE_SNAPSHOT_DESIGN.md): wallet
 * profiles, value flows, signals, score histories and remembered balances, with a header naming the application
 * version and, per network, the last block fully applied to that state (the watermark the indexers resume from).
 *
 * <p>Not in a snapshot (documented): rule-engine windows, the dry-run event history, recently retracted ids, labels
 * created by freezes (re-applied from the freeze backfill store), the approval/admin-change dedup set.
 *
 * <p>{@link #write} must run while ingestion is stopped (orderly shutdown) or on a consistent copy: it reads each store
 * under its own lock only. {@link #read} is all or nothing: everything is parsed and checked before {@link #install}
 * changes anything, so a corrupted or unknown snapshot leaves the pipeline empty, never half restored.
 */
public final class WorkingSetSnapshot {

    /** "CSWS": ChainSentinel working set. */
    public static final int MAGIC = 0x43535753;
    public static final int FORMAT = 1;
    private static final int END = 0x454E4421; // "END!": a snapshot cut short is refused

    public record Header(String appVersion, Instant createdAt, Map<Network, Long> watermarks) {
        public Header { watermarks = Map.copyOf(watermarks); }
    }

    /** What a snapshot holds, parsed and checked, not yet installed. */
    public record Contents(Header header, List<WalletProfileAccumulator> profiles, List<ValueFlow> flows, List<Signal> signals,
                           Map<Address, List<RiskScore>> scores, Set<Network> authoritative,
                           List<Map.Entry<String, BigDecimal>> balances) {
        public Counts counts() {
            int s = 0;
            for (List<RiskScore> l : scores.values()) s += l.size();
            return new Counts(profiles.size(), flows.size(), signals.size(), s, balances.size());
        }
    }

    public record Counts(int profiles, int flows, int signals, int scores, int balances) {}

    private WorkingSetSnapshot() {}

    public static Counts write(DataOutput out, IntelligencePipeline p, Header h) throws IOException {
        out.writeInt(MAGIC);
        out.writeByte(FORMAT);
        writeString(out, h.appVersion());
        writeInstant(out, h.createdAt());
        out.writeInt(h.watermarks().size());
        for (Map.Entry<Network, Long> e : new java.util.TreeMap<>(h.watermarks()).entrySet()) {
            writeNetwork(out, e.getKey());
            out.writeLong(e.getValue());
        }
        int profiles = p.profiles().writeSnapshot(out);
        List<ValueFlow> flows = new ArrayList<>(p.flows().all());
        flows.sort(io.openchain.intelligence.flow.FlowOrder.CHRONOLOGICAL); // total order: deterministic
        out.writeInt(flows.size());
        for (ValueFlow f : flows) RecordCodecs.writeFlow(out, f);
        List<Signal> signals = p.signals().findAll(); // insertion order: it decides which are evicted first
        out.writeInt(signals.size());
        for (Signal s : signals) RecordCodecs.writeSignal(out, s);
        Map<Address, List<RiskScore>> scores = new java.util.TreeMap<>(java.util.Comparator.comparing((Address a) -> a.network().id())
                .thenComparing(Address::value));
        scores.putAll(p.scoring().repository().all());
        out.writeInt(scores.size());
        int scoreCount = 0;
        for (List<RiskScore> l : scores.values()) {
            out.writeInt(l.size());
            for (RiskScore s : l) RecordCodecs.writeScore(out, s);
            scoreCount += l.size();
        }
        Set<Network> auth = p.balances().authoritativeNetworks();
        out.writeInt(auth.size());
        for (Network n : new java.util.TreeSet<>(auth)) writeNetwork(out, n);
        List<Map.Entry<String, BigDecimal>> balances = p.balances().entries();
        out.writeInt(balances.size());
        for (Map.Entry<String, BigDecimal> e : balances) {
            writeString(out, e.getKey());
            writeBigInteger(out, e.getValue().unscaledValue());
            out.writeInt(e.getValue().scale());
        }
        out.writeInt(END);
        return new Counts(profiles, flows.size(), signals.size(), scoreCount, balances.size());
    }

    /** Parses and checks a whole snapshot; installs nothing. */
    public static Contents read(DataInput in) throws IOException {
        if (in.readInt() != MAGIC) throw new SnapshotFormatException("not a working-set snapshot");
        expectVersion(in, FORMAT, "working-set snapshot");
        String version = readString(in);
        Instant createdAt = readInstant(in);
        Map<Network, Long> watermarks = new EnumMap<>(Network.class);
        for (int i = 0, n = RecordCodecs.count(in, Network.values().length); i < n; i++) watermarks.put(readNetwork(in), in.readLong());
        Header h = new Header(version, createdAt, watermarks);
        List<WalletProfileAccumulator> profiles = WalletProfileService.readSnapshot(in);
        int nf = RecordCodecs.count(in, Integer.MAX_VALUE);
        List<ValueFlow> flows = new ArrayList<>(Math.min(nf, 1 << 20));
        for (int i = 0; i < nf; i++) flows.add(RecordCodecs.readFlow(in, RecordCodecs.Canon.NONE));
        int ns = RecordCodecs.count(in, Integer.MAX_VALUE);
        List<Signal> signals = new ArrayList<>(Math.min(ns, 1 << 20));
        for (int i = 0; i < ns; i++) signals.add(RecordCodecs.readSignal(in, RecordCodecs.Canon.NONE));
        Map<Address, List<RiskScore>> scores = new LinkedHashMap<>();
        for (int i = 0, na = RecordCodecs.count(in, Integer.MAX_VALUE); i < na; i++) {
            int k = RecordCodecs.count(in, 1_000_000);
            List<RiskScore> l = new ArrayList<>(k);
            for (int j = 0; j < k; j++) l.add(RecordCodecs.readScore(in, RecordCodecs.Canon.NONE));
            if (!l.isEmpty()) scores.put(l.getFirst().address(), l);
        }
        Set<Network> auth = java.util.EnumSet.noneOf(Network.class);
        for (int i = 0, n = RecordCodecs.count(in, Network.values().length); i < n; i++) auth.add(readNetwork(in));
        int nb = RecordCodecs.count(in, Integer.MAX_VALUE);
        List<Map.Entry<String, BigDecimal>> balances = new ArrayList<>(Math.min(nb, 1 << 20));
        for (int i = 0; i < nb; i++) balances.add(Map.entry(readString(in), new BigDecimal(readBigInteger(in), in.readInt())));
        if (in.readInt() != END) throw new SnapshotFormatException("working-set snapshot not terminated");
        return new Contents(h, profiles, flows, signals, scores, auth, balances);
    }

    /**
     * Installs parsed contents into an empty pipeline (start-up, before the indexers): profiles first, so that flows share
     * their address instances (CanonicalInstances), then flows, signals, scores and balances, in their stored order.
     */
    public static Counts install(Contents c, IntelligencePipeline p) {
        if (p.flows().size() > 0 || p.profiles().size() > 0 || p.signals().size() > 0)
            throw new IllegalStateException("a snapshot is installed into an empty working set only");
        p.profiles().install(c.profiles());
        for (ValueFlow f : c.flows()) p.flows().add(p.canonical(f));
        for (Signal s : c.signals()) p.signals().saveIfAbsent(s);
        c.scores().forEach((a, l) -> { for (RiskScore s : l) p.scoring().repository().append(s); });
        for (Network n : c.authoritative()) p.balances().markAuthoritative(n);
        for (Map.Entry<String, BigDecimal> e : c.balances()) p.balances().recordSenderBalanceBefore(e.getKey(), e.getValue());
        return c.counts();
    }
}
