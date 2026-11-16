package io.openchain.intelligence.snapshot;

import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.function.UnaryOperator;

/**
 * Binary primitives of the working-set snapshot (ADR-0024 step 3d, docs/PERSISTENCE_SNAPSHOT_DESIGN.md). Nullable values
 * carry a presence byte; strings are UTF-8 with a length (no 64 KB limit of {@code writeUTF}). Every record format built
 * on these is versioned by its writer: a reader refuses an unknown version ({@link SnapshotFormatException}) and the
 * application then starts empty, never with a partial state.
 */
public final class SnapshotIO {
    private SnapshotIO() {}

    /** A snapshot that cannot be read with this version of the application (unknown format, truncated, corrupted). */
    public static final class SnapshotFormatException extends RuntimeException {
        public SnapshotFormatException(String message) { super(message); }
    }

    public static void expectVersion(DataInput in, int expected, String what) throws IOException {
        int v = in.readUnsignedByte();
        if (v != expected) throw new SnapshotFormatException(what + ": format version " + v + ", expected " + expected);
    }

    public static void writeString(DataOutput out, String s) throws IOException {
        if (s == null) { out.writeInt(-1); return; }
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    public static String readString(DataInput in) throws IOException {
        int n = in.readInt();
        if (n == -1) return null;
        if (n < 0 || n > 64 * 1024 * 1024) throw new SnapshotFormatException("invalid string length " + n);
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void writeInstant(DataOutput out, Instant t) throws IOException {
        out.writeBoolean(t != null);
        if (t != null) { out.writeLong(t.getEpochSecond()); out.writeInt(t.getNano()); }
    }

    public static Instant readInstant(DataInput in) throws IOException {
        return in.readBoolean() ? Instant.ofEpochSecond(in.readLong(), in.readInt()) : null;
    }

    public static void writeDuration(DataOutput out, Duration d) throws IOException {
        out.writeLong(d.getSeconds());
        out.writeInt(d.getNano());
    }

    public static Duration readDuration(DataInput in) throws IOException { return Duration.ofSeconds(in.readLong(), in.readInt()); }

    public static void writeBigInteger(DataOutput out, BigInteger v) throws IOException {
        byte[] b = v.toByteArray();
        out.writeInt(b.length);
        out.write(b);
    }

    public static BigInteger readBigInteger(DataInput in) throws IOException {
        int n = in.readInt();
        if (n <= 0 || n > 4096) throw new SnapshotFormatException("invalid integer length " + n);
        byte[] b = new byte[n];
        in.readFully(b);
        return new BigInteger(b);
    }

    public static void writeNetwork(DataOutput out, Network n) throws IOException { writeString(out, n.id()); }

    public static Network readNetwork(DataInput in) throws IOException {
        String id = readString(in);
        try {
            return Network.fromId(id);
        } catch (RuntimeException e) {
            throw new SnapshotFormatException("unknown network '" + id + "'");
        }
    }

    public static void writeAddress(DataOutput out, Address a) throws IOException {
        out.writeBoolean(a != null);
        if (a != null) { writeNetwork(out, a.network()); writeString(out, a.value()); }
    }

    /** @param canonical shares equal instances (CanonicalInstances); identity for none */
    public static Address readAddress(DataInput in, UnaryOperator<Address> canonical) throws IOException {
        if (!in.readBoolean()) return null;
        Network n = readNetwork(in);
        return canonical.apply(new Address(n, readString(in)));
    }

    public static void writeToken(DataOutput out, Token t) throws IOException {
        writeNetwork(out, t.network());
        writeString(out, t.contract());
        writeString(out, t.symbol());
        out.writeInt(t.decimals());
    }

    public static Token readToken(DataInput in, UnaryOperator<Token> canonical) throws IOException {
        Network n = readNetwork(in);
        return canonical.apply(new Token(n, readString(in), readString(in), in.readInt()));
    }

    /** Wraps an IOException of an in-memory stream (cannot happen) or a real one, unchecked. */
    public static UncheckedIOException unchecked(IOException e) { return new UncheckedIOException(e); }
}
