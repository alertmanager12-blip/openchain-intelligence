import { NetworkStatus } from '../core/models';
import { netLabel } from './format';

/**
 * The coverage sentence of a verdict built from a network's live indexing (spec 2026-11-18, decision 3). The API gives no
 * date since when a network is covered, so none is claimed: the sentence names the network, the block reached and the lag,
 * and says that earlier activity is not covered. It never says that nothing exists.
 */
export function networkCoverageSentence(s: NetworkStatus): string {
  const name = netLabel(s.network);
  const tail = ' Activity before monitoring started is not covered.';
  if (s.indexerLag === null || s.indexerLag === undefined || !s.healthy) {
    return `Based on the transfers this instance indexes on ${name}; the chain head is unknown right now.${tail}`;
  }
  const block = (s.lastIndexedBlock ?? s.headBlock).toLocaleString('en-US');
  const lag = s.indexerLag === 1 ? '1 block behind' : `${s.indexerLag.toLocaleString('en-US')} blocks behind`;
  return `Based on the transfers this instance indexes on ${name} (block ${block}, ${lag}).${tail}`;
}
