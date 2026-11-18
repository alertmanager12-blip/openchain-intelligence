package io.openchain.app.summary;

import io.openchain.intelligence.investigation.SummaryCheck;
import java.util.List;

/** One AI provider that drafts a summary (ADR-0022 amendment 5). Never trusted: its draft is checked on the server. */
public interface SummaryWriter {

    /**
     * @param sentences the parsed draft, or null when it failed
     * @param model     the model that answered, as reported by the provider
     * @param failure   why there is no draft (refusal, API error, malformed answer), or null
     */
    record Draft(List<SummaryCheck.Sentence> sentences, String model, String failure) {
        public static Draft failed(String model, String failure) { return new Draft(null, model, failure); }
    }

    Draft write(String system, String user);
}
