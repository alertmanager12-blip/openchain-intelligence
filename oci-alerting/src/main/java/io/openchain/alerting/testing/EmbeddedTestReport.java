package io.openchain.alerting.testing;

import java.util.List;

/** Results of a rule's embedded {@code examples} and {@code tests}. */
public record EmbeddedTestReport(String ruleRef, List<CaseResult> results) {

    public enum Kind { EXAMPLE, TEST }

    public record CaseResult(Kind kind, String name, boolean passed, String message) {}

    public EmbeddedTestReport { results = List.copyOf(results); }

    public boolean passed() { return results.stream().allMatch(CaseResult::passed); }

    public List<CaseResult> failures() { return results.stream().filter(r -> !r.passed()).toList(); }
}
