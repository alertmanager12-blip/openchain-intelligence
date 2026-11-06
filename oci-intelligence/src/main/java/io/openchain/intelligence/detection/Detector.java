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
}
