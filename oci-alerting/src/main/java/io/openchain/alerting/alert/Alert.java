package io.openchain.alerting.alert;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Network;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable alert snapshot. All changes go through {@link AlertService}, which records them in {@link #history()}.
 * {@code firstSeen}/{@code lastSeen} are event times; {@code createdAt}/{@code updatedAt} and history are wall clock.
 * An alert reports observed behaviour matching a rule; it is never a judgement about the address owner.
 */
public final class Alert {

    /** Status change (or annotation such as escalation, where {@code from == to}). */
    public record HistoryEntry(AlertStatus from, AlertStatus to, String actor, Instant at, String reason) {}

    public record Comment(String author, Instant at, String text) {}

    public static final int MAX_EVENT_IDS = 500;

    private final String id;
    private final String ruleId;
    private final String ruleVersion;
    private final String title;
    private final Severity severity;
    private final Priority priority;
    private final AlertStatus status;
    private final EvidenceLevel evidenceLevel;
    private final Network network;
    private final String groupKey;
    private final String dedupKey;
    private final List<String> matchedEventIds;
    private final Map<String, Object> evidence;
    private final Instant firstSeen;
    private final Instant lastSeen;
    private final long occurrenceCount;
    private final String assignee;
    private final List<Comment> comments;
    private final List<HistoryEntry> history;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final int escalationLevel;
    private final Instant lastEscalatedAt;
    private final List<String> originChain;
    private final int hop;

    private Alert(Builder b) {
        this.id = Objects.requireNonNull(b.id);
        this.ruleId = Objects.requireNonNull(b.ruleId);
        this.ruleVersion = b.ruleVersion;
        this.title = b.title;
        this.severity = Objects.requireNonNull(b.severity);
        this.priority = Objects.requireNonNull(b.priority);
        this.status = Objects.requireNonNull(b.status);
        this.evidenceLevel = b.evidenceLevel;
        this.network = b.network;
        this.groupKey = b.groupKey;
        this.dedupKey = b.dedupKey;
        this.matchedEventIds = List.copyOf(b.matchedEventIds);
        this.evidence = Collections.unmodifiableMap(new LinkedHashMap<>(b.evidence));
        this.firstSeen = b.firstSeen;
        this.lastSeen = b.lastSeen;
        this.occurrenceCount = b.occurrenceCount;
        this.assignee = b.assignee;
        this.comments = List.copyOf(b.comments);
        this.history = List.copyOf(b.history);
        this.createdAt = b.createdAt;
        this.updatedAt = b.updatedAt;
        this.escalationLevel = b.escalationLevel;
        this.lastEscalatedAt = b.lastEscalatedAt;
        this.originChain = List.copyOf(b.originChain);
        this.hop = b.hop;
    }

    public String id() { return id; }
    public String ruleId() { return ruleId; }
    public String ruleVersion() { return ruleVersion; }
    public String title() { return title; }
    public Severity severity() { return severity; }
    public Priority priority() { return priority; }
    public AlertStatus status() { return status; }
    public EvidenceLevel evidenceLevel() { return evidenceLevel; }
    public Network network() { return network; }
    public String groupKey() { return groupKey; }
    public String dedupKey() { return dedupKey; }
    public List<String> matchedEventIds() { return matchedEventIds; }
    public Map<String, Object> evidence() { return evidence; }
    public Instant firstSeen() { return firstSeen; }
    public Instant lastSeen() { return lastSeen; }
    public long occurrenceCount() { return occurrenceCount; }
    public String assignee() { return assignee; }
    public List<Comment> comments() { return comments; }
    public List<HistoryEntry> history() { return history; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public int escalationLevel() { return escalationLevel; }
    public Instant lastEscalatedAt() { return lastEscalatedAt; }
    public List<String> originChain() { return originChain; }
    public int hop() { return hop; }

    public Builder toBuilder() { return new Builder(this); }

    public static Builder builder() { return new Builder(); }

    @Override public String toString() {
        return "Alert[" + id + " " + ruleId + "@" + ruleVersion + " " + status + " " + priority + " x" + occurrenceCount + " " + groupKey + "]";
    }

    public static final class Builder {
        private String id, ruleId, ruleVersion, title, groupKey, dedupKey, assignee;
        private Severity severity;
        private Priority priority;
        private AlertStatus status = AlertStatus.DETECTED;
        private EvidenceLevel evidenceLevel = EvidenceLevel.ANOMALY;
        private Network network;
        private List<String> matchedEventIds = new ArrayList<>();
        private Map<String, Object> evidence = new LinkedHashMap<>();
        private Instant firstSeen, lastSeen, createdAt, updatedAt, lastEscalatedAt;
        private long occurrenceCount = 1;
        private List<Comment> comments = new ArrayList<>();
        private List<HistoryEntry> history = new ArrayList<>();
        private int escalationLevel;
        private List<String> originChain = new ArrayList<>();
        private int hop;

        private Builder() {}

        private Builder(Alert a) {
            id = a.id; ruleId = a.ruleId; ruleVersion = a.ruleVersion; title = a.title; groupKey = a.groupKey;
            dedupKey = a.dedupKey; assignee = a.assignee; severity = a.severity; priority = a.priority; status = a.status;
            evidenceLevel = a.evidenceLevel; network = a.network; matchedEventIds = new ArrayList<>(a.matchedEventIds);
            evidence = new LinkedHashMap<>(a.evidence); firstSeen = a.firstSeen; lastSeen = a.lastSeen;
            createdAt = a.createdAt; updatedAt = a.updatedAt; lastEscalatedAt = a.lastEscalatedAt;
            occurrenceCount = a.occurrenceCount; comments = new ArrayList<>(a.comments); history = new ArrayList<>(a.history);
            escalationLevel = a.escalationLevel; originChain = new ArrayList<>(a.originChain); hop = a.hop;
        }

        public Builder id(String v) { id = v; return this; }
        public Builder ruleId(String v) { ruleId = v; return this; }
        public Builder ruleVersion(String v) { ruleVersion = v; return this; }
        public Builder title(String v) { title = v; return this; }
        public Builder severity(Severity v) { severity = v; return this; }
        public Builder priority(Priority v) { priority = v; return this; }
        public Builder status(AlertStatus v) { status = v; return this; }
        public Builder evidenceLevel(EvidenceLevel v) { evidenceLevel = v; return this; }
        public Builder network(Network v) { network = v; return this; }
        public Builder groupKey(String v) { groupKey = v; return this; }
        public Builder dedupKey(String v) { dedupKey = v; return this; }
        public Builder evidence(Map<String, Object> v) { evidence = new LinkedHashMap<>(v); return this; }
        public Builder firstSeen(Instant v) { firstSeen = v; return this; }
        public Builder lastSeen(Instant v) { lastSeen = v; return this; }
        public Builder occurrenceCount(long v) { occurrenceCount = v; return this; }
        public Builder assignee(String v) { assignee = v; return this; }
        public Builder createdAt(Instant v) { createdAt = v; return this; }
        public Builder updatedAt(Instant v) { updatedAt = v; return this; }
        public Builder escalationLevel(int v) { escalationLevel = v; return this; }
        public Builder lastEscalatedAt(Instant v) { lastEscalatedAt = v; return this; }
        public Builder originChain(List<String> v) { originChain = new ArrayList<>(v); return this; }
        public Builder hop(int v) { hop = v; return this; }
        public Builder addComment(Comment c) { comments.add(c); return this; }
        public Builder addHistory(HistoryEntry h) { history.add(h); return this; }

        /** Union preserving order, bounded to {@link #MAX_EVENT_IDS} (most recent kept). */
        public Builder addEventIds(List<String> ids) {
            LinkedHashSet<String> set = new LinkedHashSet<>(matchedEventIds);
            set.addAll(ids);
            List<String> all = new ArrayList<>(set);
            matchedEventIds = all.size() > MAX_EVENT_IDS ? new ArrayList<>(all.subList(all.size() - MAX_EVENT_IDS, all.size())) : all;
            return this;
        }

        public Alert build() { return new Alert(this); }
    }
}
