package io.openchain.intelligence.detection;

import io.openchain.core.model.Address;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/** Deterministic signal ids: same detector + address + discriminator (tx hash or window) => same id. */
public final class SignalIds {
    private SignalIds() {}

    public static String of(String detectorQualifiedId, Address address, String discriminator) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((detectorQualifiedId + "\u0000" + address + "\u0000" + discriminator).getBytes(StandardCharsets.UTF_8));
            return "sig_" + HexFormat.of().formatHex(h, 0, 16);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Tumbling window bucket key, e.g. {@code w3600:490000}. */
    public static String window(Instant t, Duration w) {
        long s = Math.max(1, w.toSeconds());
        return "w" + s + ":" + Math.floorDiv(t.getEpochSecond(), s);
    }
}
