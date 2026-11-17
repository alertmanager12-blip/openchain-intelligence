package io.openchain.app.trace;

import io.openchain.intelligence.trace.FrozenSource;
import io.openchain.intelligence.trace.Trace;
import io.openchain.intelligence.trace.TraceDiff;
import tools.jackson.databind.json.JsonMapper;

/** JSON of what a trace artifact stores. Records only; the same object always gives the same text (sorted inputs). */
public final class TraceJson {
    private TraceJson() {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static String write(Object o) { return JSON.writeValueAsString(o); }

    public static Trace.Request request(String json) { return JSON.readValue(json, Trace.Request.class); }

    public static Trace.Result result(String json) { return JSON.readValue(json, Trace.Result.class); }

    public static FrozenSource.Snapshot evidence(String json) { return JSON.readValue(json, FrozenSource.Snapshot.class); }

    public static TraceDiff.Diff diff(String json) { return JSON.readValue(json, TraceDiff.Diff.class); }

    public static io.openchain.intelligence.investigation.InvestigationPlan.Inputs inputs(String json) {
        return JSON.readValue(json, io.openchain.intelligence.investigation.InvestigationPlan.Inputs.class);
    }

    public static java.util.List<io.openchain.intelligence.investigation.InvestigationPlan.Assertion> assertions(String json) {
        return java.util.List.of(JSON.readValue(json, io.openchain.intelligence.investigation.InvestigationPlan.Assertion[].class));
    }
}
