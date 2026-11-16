package io.openchain.alerting.engine;

import java.math.BigDecimal;

/**
 * Re-evaluation of an aggregation rule for one group key after an event was retracted (chain reorganisation): does a
 * window ending in the alert's time range still pass the threshold, by the events the engine holds?
 *
 * @param value      aggregate of the passing window (STILL_MET), or of the window ending at the end of the range
 *                   otherwise; null when no event is left or the value is undefined
 * @param eventCount events in that window
 */
public record ThresholdCheck(Verdict verdict, String function, String operator, BigDecimal threshold, BigDecimal value,
                             int eventCount) {

    public enum Verdict {
        /** A window of the range still passes: the alert is still justified. */
        STILL_MET,
        /** No window of the range passes, and the engine still holds every event it used for that range. */
        NO_LONGER_MET,
        /**
         * No window passes among the events still held, but part of the state is gone (evicted, rule changed, engine
         * restarted): nothing can be concluded.
         */
        UNVERIFIABLE
    }
}
