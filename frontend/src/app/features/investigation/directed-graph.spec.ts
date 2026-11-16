import { Graph, GraphEdge } from '../../core/models';
import { keyed, structuralStops } from './investigation';

const tok = { network: 'tron', contract: 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t', symbol: 'USDT', decimals: 6 } as GraphEdge['token'];

function edge(first: string, last: string): GraphEdge {
  return { id: 'e0', source: 'A', target: 'B', txHash: '0x1', token: tok, rawAmount: '1', timestamp: last, firstTimestamp: first, eventIds: ['x'], count: 2 };
}

describe('directed graph page (Fund Flow Trace P0-C)', () => {
  const g = (e: GraphEdge): Graph => ({ root: 'A', nodes: [], edges: [e], truncated: false });

  it('keeps an edge whose transfers overlap the date range, not only by its last transfer (G10)', () => {
    const e = edge('2026-11-01T00:00:00Z', '2026-11-10T00:00:00Z');
    expect(keyed(g(e), { from: '2026-11-03T00:00:00Z', to: '2026-11-05T00:00:00Z' })).toHaveLength(1);
    expect(keyed(g(e), { to: '2026-10-31T00:00:00Z' })).toHaveLength(0);
    expect(keyed(g(e), { from: '2026-11-11T00:00:00Z' })).toHaveLength(0);
  });

  it('shows the stops that make the graph incomplete, not the near-universal ones', () => {
    const stops = structuralStops([
      { address: 'A', token: null, reason: 'UNKNOWN_OPENING_BALANCE', detail: '' },
      { address: 'B', token: null, reason: 'SERVICE_LIKE', detail: '' },
      { address: 'C', token: null, reason: 'DEPTH', detail: '' },
      { address: 'D', token: null, reason: 'SAME_TIME_ORDER', detail: '' },
    ]);
    expect(stops.map((s) => s.reason)).toEqual(['SERVICE_LIKE', 'DEPTH']);
  });
});
