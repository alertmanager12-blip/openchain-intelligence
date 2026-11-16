package io.openchain.intelligence.snapshot;

import static io.openchain.intelligence.snapshot.SnapshotIO.*;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.core.model.Token;
import io.openchain.core.risk.RiskLevel;
import io.openchain.core.risk.RiskScore;
import io.openchain.intelligence.flow.ValueFlow;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Exact binary form of the records held in the working set (ADR-0024 step 3d). Each record starts with its format
 * version. Enumerations are written by name (a reordering of the enum does not change the meaning of a snapshot; an
 * unknown name is refused). Doubles are written bit for bit.
 */
public final class RecordCodecs {
    private RecordCodecs() {}

    public static final int FLOW_FORMAT = 1, SIGNAL_FORMAT = 1, SCORE_FORMAT = 1;

    /** Canonicalisation applied while reading (shared instances, CanonicalInstances); identity for none. */
    public record Canon(UnaryOperator<Address> address, UnaryOperator<Token> token) {
        public static final Canon NONE = new Canon(UnaryOperator.identity(), UnaryOperator.identity());
    }

    // ---- value flows

    public static void writeFlow(DataOutput out, ValueFlow f) throws IOException {
        out.writeByte(FLOW_FORMAT);
        writeString(out, f.eventId());
        writeNetwork(out, f.network());
        writeString(out, f.txHash());
        writeInstant(out, f.timestamp());
        writeAddress(out, f.from());
        writeAddress(out, f.to());
        writeToken(out, f.token());
        writeBigInteger(out, f.rawAmount());
    }

    public static ValueFlow readFlow(DataInput in, Canon c) throws IOException {
        expectVersion(in, FLOW_FORMAT, "value flow");
        return new ValueFlow(readString(in), readNetwork(in), readString(in), readInstant(in),
                readAddress(in, c.address()), readAddress(in, c.address()), readToken(in, c.token()), readBigInteger(in));
    }

    // ---- signals

    public static void writeSignal(DataOutput out, Signal s) throws IOException {
        out.writeByte(SIGNAL_FORMAT);
        writeString(out, s.id());
        writeString(out, s.type().name());
        writeAddress(out, s.address());
        writeString(out, s.txHash());
        writeString(out, s.severity().name());
        out.writeDouble(s.confidence());
        writeString(out, s.evidenceLevel().name());
        out.writeInt(s.evidence().size());
        for (Signal.Evidence e : s.evidence()) {
            writeString(out, e.kind());
            writeString(out, e.description());
            writeString(out, e.reference());
            writeString(out, e.source());
            writeInstant(out, e.observedAt());
        }
        writeString(out, s.detectorId());
        writeInstant(out, s.detectedAt());
        writeString(out, s.title());
        writeString(out, s.summary());
    }

    public static Signal readSignal(DataInput in, Canon c) throws IOException {
        expectVersion(in, SIGNAL_FORMAT, "signal");
        String id = readString(in);
        SignalType type = constant(SignalType.class, readString(in));
        Address address = readAddress(in, c.address());
        String txHash = readString(in);
        Severity severity = constant(Severity.class, readString(in));
        double confidence = in.readDouble();
        Signal.EvidenceLevel level = constant(Signal.EvidenceLevel.class, readString(in));
        int n = count(in, 10_000);
        List<Signal.Evidence> evidence = new ArrayList<>(n);
        for (int i = 0; i < n; i++) evidence.add(new Signal.Evidence(readString(in), readString(in), readString(in), readString(in), readInstant(in)));
        return new Signal(id, type, address, txHash, severity, confidence, level, evidence, readString(in), readInstant(in),
                readString(in), readString(in));
    }

    // ---- risk scores

    public static void writeScore(DataOutput out, RiskScore s) throws IOException {
        out.writeByte(SCORE_FORMAT);
        writeAddress(out, s.address());
        out.writeInt(s.score());
        writeString(out, s.level().name());
        out.writeInt(s.contributions().size());
        for (RiskScore.Contribution k : s.contributions()) {
            writeString(out, k.factor());
            writeString(out, k.signalId());
            writeString(out, k.source());
            out.writeDouble(k.weight());
            out.writeDouble(k.confidence());
            out.writeLong(k.ageSeconds());
            out.writeDouble(k.points());
            writeString(out, k.explanation());
            writeString(out, k.title());
            writeString(out, k.summary());
        }
        writeString(out, s.method());
        writeString(out, s.modelVersion());
        writeInstant(out, s.computedAt());
    }

    public static RiskScore readScore(DataInput in, Canon c) throws IOException {
        expectVersion(in, SCORE_FORMAT, "risk score");
        Address a = readAddress(in, c.address());
        int score = in.readInt();
        RiskLevel level = constant(RiskLevel.class, readString(in));
        int n = count(in, 10_000);
        List<RiskScore.Contribution> cs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            cs.add(new RiskScore.Contribution(readString(in), readString(in), readString(in), in.readDouble(), in.readDouble(),
                    in.readLong(), in.readDouble(), readString(in), readString(in), readString(in)));
        }
        return new RiskScore(a, score, level, cs, readString(in), readString(in), readInstant(in));
    }

    // ---- helpers

    public static int count(DataInput in, int max) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max) throw new SnapshotFormatException("invalid count " + n + " (max " + max + ")");
        return n;
    }

    static <E extends Enum<E>> E constant(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name);
        } catch (RuntimeException e) {
            throw new SnapshotFormatException("unknown " + type.getSimpleName() + " '" + name + "'");
        }
    }
}
