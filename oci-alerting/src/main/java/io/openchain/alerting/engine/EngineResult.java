package io.openchain.alerting.engine;

import java.util.List;

/**
 * What the engine did with one event.
 *
 * @param retractedEventId set when {@code disposition == RETRACTED}: the event id whose contribution was removed
 */
public record EngineResult(Disposition disposition, List<RuleMatch> matches, String retractedEventId) {

    public enum Disposition { PROCESSED, DUPLICATE, LATE_DROPPED, RETRACTED, RETRACTION_IGNORED, LOOP_SUPPRESSED, INVALID }

    public EngineResult { matches = List.copyOf(matches); }

    static EngineResult of(Disposition d) { return new EngineResult(d, List.of(), null); }
}
