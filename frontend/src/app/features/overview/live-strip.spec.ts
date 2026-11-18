import { NetworkStatus, Alert } from '../../core/models';
import { stripState, ticks } from './live-strip';

const net = (o: Partial<NetworkStatus>): NetworkStatus => ({ network: 'tron', headBlock: 1000, finalizedBlock: 990, healthy: true, providerId: 'p',
  latencyMillis: 5, checkedAt: '2026-11-18T20:00:00Z', detail: null, lastIndexedBlock: 1000, indexerLag: 0, ...o });
const alert = (blockNumber: number | null): Alert => ({ blockNumber, severity: 'HIGH', network: 'tron' }) as unknown as Alert;

/** Spec 2026-11-18: the live strip shows only what the indexers report. */
describe('live strip', () => {
  it('is live up to 20 blocks behind', () => {
    expect(stripState(net({ indexerLag: 0 })).state).toBe('live');
    expect(stripState(net({ indexerLag: 20 })).state).toBe('live');
  });

  it('is behind beyond 20 blocks, with the number', () => {
    expect(stripState(net({ indexerLag: 21 }))).toEqual({ state: 'behind', label: 'Behind (21 blocks)' });
  });

  it('is unreachable when the provider fails or the head is unknown, never live', () => {
    expect(stripState(net({ healthy: false })).state).toBe('unreachable');
    expect(stripState(net({ indexerLag: null })).state).toBe('unreachable');
  });

  it('places alerts by block relative to the head and drops those out of the span or without a block', () => {
    expect(ticks(1000, [alert(990), alert(10), alert(null)], 100)).toEqual([{ pos: 0.9, severity: 'HIGH' }]);
  });
});
