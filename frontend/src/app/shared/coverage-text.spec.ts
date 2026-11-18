import { NetworkStatus } from '../core/models';
import { networkCoverageSentence } from './coverage-text';

const base: NetworkStatus = { network: 'tron', headBlock: 88105043, finalizedBlock: 88105020, healthy: true, providerId: 'p', latencyMillis: 10,
  checkedAt: '2026-11-18T20:00:00Z', detail: null, lastIndexedBlock: 88105040, indexerLag: 3 };

/** Spec 2026-11-18, decision 3 as corrected by the plan: no "since" date exists, so none is claimed. */
describe('networkCoverageSentence', () => {
  it('names the network, the block and the lag', () => {
    expect(networkCoverageSentence(base)).toBe(
      'Based on the transfers this instance indexes on TRON (block 88,105,040, 3 blocks behind). Activity before monitoring started is not covered.');
  });

  it('says when the chain head is unknown, never "no lag"', () => {
    expect(networkCoverageSentence({ ...base, indexerLag: null })).toBe(
      'Based on the transfers this instance indexes on TRON; the chain head is unknown right now. Activity before monitoring started is not covered.');
  });

  it('never claims that nothing exists', () => {
    for (const s of [base, { ...base, indexerLag: null }, { ...base, indexerLag: 0 }]) {
      expect(networkCoverageSentence(s)).not.toMatch(/nothing|no .* found|none/i);
    }
  });
});
