package io.openchain.intelligence.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.SignalType;
import io.openchain.intelligence.Fx;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Signal format 2 carries the listed category; format 1 (snapshots written before it) is still read. */
class RecordCodecsSignalTest {
    static Signal signal(String listedCategory) {
        return new Signal("s1", SignalType.INDIRECT_EXPOSURE, Fx.a("me"), "0xtx", Severity.MEDIUM, 0.5,
                Signal.EvidenceLevel.RISK_SIGNAL, List.of(new Signal.Evidence("hops", "2", "0xtx", "exposure@1", Fx.T0)),
                "exposure@1", Fx.T0, "Indirect exposure", "Received funds...", listedCategory);
    }

    static byte[] write(Signal s) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        RecordCodecs.writeSignal(new DataOutputStream(b), s);
        return b.toByteArray();
    }

    static Signal read(byte[] b) throws IOException {
        return RecordCodecs.readSignal(new DataInputStream(new ByteArrayInputStream(b)), RecordCodecs.Canon.NONE);
    }

    @Test void theListedCategoryRoundTrips() throws IOException {
        assertThat(read(write(signal("ISSUER_FROZEN")))).isEqualTo(signal("ISSUER_FROZEN"));
        assertThat(read(write(signal(null)))).isEqualTo(signal(null));
    }

    @Test void aFormat1SignalIsReadWithoutCategory() throws IOException {
        byte[] v2 = write(signal(null));
        byte[] v1 = Arrays.copyOf(v2, v2.length - 4); // format 1 ends after the summary (a null string is 4 bytes)
        v1[0] = 1;
        assertThat(read(v1)).isEqualTo(signal(null));
    }

    @Test void anUnknownFormatIsRefused() throws IOException {
        byte[] b = write(signal(null));
        b[0] = 3;
        assertThatThrownBy(() -> read(b)).isInstanceOf(SnapshotIO.SnapshotFormatException.class);
    }
}
