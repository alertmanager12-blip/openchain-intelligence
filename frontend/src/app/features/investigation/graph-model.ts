import { GraphEdge, RiskLevel } from '../../core/models';

export const RISK_VAR: Record<RiskLevel, string> = { CRITICAL: '--risk-critical', HIGH: '--risk-high', MEDIUM: '--risk-medium', LOW: '--risk-low', UNKNOWN: '--risk-unknown' };

export interface KeyedEdge { key: string; edge: GraphEdge }
export type GraphSelection = { kind: 'node' | 'edge'; id: string } | null;
export interface GraphCommand { kind: 'fit' | 'png' | 'path' | 'clear'; target?: string; n: number }

/** Edge width 1..5 px: log-scaled amount relative to the largest transfer of the same token in the graph. */
export function edgeWidths(edges: KeyedEdge[]): Map<string, number> {
  const amount = (e: GraphEdge) => Number(e.rawAmount) / 10 ** e.token.decimals || 0;
  const tok = (e: GraphEdge) => `${e.token.network}:${e.token.symbol}`;
  const max = new Map<string, number>();
  for (const { edge } of edges) max.set(tok(edge), Math.max(max.get(tok(edge)) ?? 0, amount(edge)));
  const out = new Map<string, number>();
  for (const { key, edge } of edges) {
    const m = max.get(tok(edge)) ?? 0;
    out.set(key, m > 0 ? 1 + (4 * Math.log10(1 + amount(edge))) / Math.log10(1 + m) : 1);
  }
  return out;
}

/**
 * What to change in the drawn graph so that it shows exactly `wanted` (ids of nodes and edges): elements to add and ids
 * to remove. Removing matters: on the same root, a narrower token, date range or depth must take away what no longer
 * matches; drawing only additions left stale transfers on screen (code review I11, 2026-11-13).
 */
export function graphDiff<T extends { data: { id?: string } }>(existingIds: Iterable<string>, wanted: T[]): { add: T[]; removeIds: string[] } {
  const want = new Set(wanted.map((e) => e.data.id!));
  const have = new Set(existingIds);
  return {
    add: wanted.filter((e) => !have.has(e.data.id!)),
    removeIds: [...have].filter((id) => !want.has(id)),
  };
}
