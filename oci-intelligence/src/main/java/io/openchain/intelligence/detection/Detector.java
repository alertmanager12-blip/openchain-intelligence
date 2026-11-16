package io.openchain.intelligence.detection;

import io.openchain.core.detection.Signal;
import java.util.List;

/**
 * Detection SPI. Implementations must be deterministic, side-effect free, explainable (every signal carries
 * evidence with references) and must never execute user-supplied code.
 */
public interface Detector {
    String id();

    int version();

    /** {@code id@version}, recorded on every signal. */
    default String qualifiedId() { return id() + "@" + version(); }

    List<Signal> detect(DetectionContext context);

    /**
     * Whether this detector sees flows of unverified tokens ({@link io.openchain.core.model.Token#isUnverified()}).
     * Default false: anyone can deploy a contract emitting {@code Transfer(from = victim, ...)}, so such flows prove
     * nothing about the parties; the engine neither runs the detector on them nor shows them in its flow store.
     * A detector that opts in must handle them itself (exposure records them as zero-weight DUSTING).
     */
    default boolean readsUnverifiedTokenFlows() { return false; }
}
