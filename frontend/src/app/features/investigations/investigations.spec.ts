import { CurrentReview, InvestigationReviews } from '../../core/models';
import { ProposalDryRun } from '../../core/models';
import { dryRunLine, historyOf, reviewNote, visibleSentence } from './investigations';

const current = (over: Partial<CurrentReview> = {}): CurrentReview => ({
  decision: 'ACCEPTED', comment: null, actor: 'apikey:analyst:oci_x', createdAt: '2026-11-17T10:00:00.123Z', version: 1, inherited: false, ...over,
});

const reviews: InvestigationReviews = {
  investigationId: 'inv2', version: 2, stale: false, decided: 1, total: 3,
  current: { as_a: current({ decision: 'REJECTED', inherited: true }) },
  history: [
    { id: 'rv1', investigationId: 'inv1', version: 1, assertionId: 'as_a', decision: 'NEEDS_EVIDENCE', comment: 'which blocks?', actor: 'x', createdAt: '2026-11-17T09:00:00Z' },
    { id: 'rv2', investigationId: 'inv1', version: 1, assertionId: 'as_a', decision: 'REJECTED', comment: null, actor: 'x', createdAt: '2026-11-17T10:00:00Z' },
    { id: 'rv3', investigationId: 'inv1', version: 1, assertionId: 'as_gone', decision: 'ACCEPTED', comment: null, actor: 'x', createdAt: '2026-11-17T11:00:00Z' },
  ],
};

describe('investigation reviews (ADR-0022 amendment 3)', () => {
  it('says who decided, when, and on which version for an inherited decision', () => {
    expect(reviewNote(current())).toBe('Accepted by apikey:analyst:oci_x on 2026-11-17 10:00:00 UTC');
    expect(reviewNote(current({ decision: 'NEEDS_EVIDENCE', inherited: true, version: 1 })))
      .toBe('Needs evidence by apikey:analyst:oci_x on 2026-11-17 10:00:00 UTC (decided on version 1)');
  });

  it('hides decided sentences only with "undecided only"', () => {
    expect(visibleSentence('as_a', reviews, false)).toBe(true);
    expect(visibleSentence('as_a', reviews, true)).toBe(false);
    expect(visibleSentence('as_b', reviews, true)).toBe(true);
    expect(visibleSentence('as_a', null, true)).toBe(true);
  });

  it('lists the reviews of one assertion, oldest first', () => {
    expect(historyOf('as_a', reviews).map((r) => r.decision)).toEqual(['NEEDS_EVIDENCE', 'REJECTED']);
    expect(historyOf('as_a', null)).toEqual([]);
  });
});

describe('rule proposals (ADR-0022 amendment 4)', () => {
  const dry = (over: Partial<ProposalDryRun> = {}): ProposalDryRun => ({
    totalEvents: 1200, eventsInScope: 40, conditionMatches: 3, triggers: 2, estimatedAlerts: 2, from: '2026-11-17T08:00:00Z',
    to: '2026-11-17T09:30:00.5Z', estimatedAlertsPerDay: 32, warnings: [], ...over,
  });

  it('states the window the dry run actually covered', () => {
    expect(dryRunLine(dry())).toBe('Dry run on 1200 event(s) from 2026-11-17 08:00:00 UTC to 2026-11-17 09:30:00 UTC: 40 in scope, 2 trigger(s), about 32.0 alert(s) per day.');
  });

  it('says when nothing could be tested', () => {
    expect(dryRunLine(dry({ totalEvents: 0, from: null, to: null }))).toContain('nothing could be tested');
  });
});
