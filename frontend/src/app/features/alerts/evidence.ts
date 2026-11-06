import { NetworkId } from '../../core/models';

/** One condition outcome as written by the engine: "field op expected (actual value) -> true". */
export interface ConditionOutcome {
  field: string;
  op: string;
  expected: string;
  actual: string;
  matched: boolean;
}

const CONDITION = /^(\S+) (\S+) (.*) \(actual (.*)\) -> (true|false)$/;

export function parseCondition(s: string): ConditionOutcome | null {
  const m = CONDITION.exec(s.trim());
  return m ? { field: m[1], op: m[2], expected: m[3], actual: m[4], matched: m[5] === 'true' } : null;
}

const OPS: Record<string, string> = {
  gt: '>', gte: '≥', lt: '<', lte: '≤', eq: '=', neq: '≠', ne: '≠', in: 'in', not_in: 'not in', between: 'between', contains: 'contains', exists: 'exists',
};

/** Engine operators come as "gte" (conditions) or "GTE" (aggregations). */
export function opSymbol(op: string): string {
  return OPS[op.toLowerCase()] ?? op.toLowerCase().replace(/_/g, ' ');
}

/** ISO-8601 duration (Java Duration.toString, e.g. "PT2H30M", "PT0.5S", "P1D") in plain words. */
export function isoDuration(d: string | null | undefined): string {
  if (!d) return '–';
  const m = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?)?$/.exec(d);
  if (!m) return d;
  const [, days, h, min, sec] = m;
  const parts: string[] = [];
  if (days) parts.push(`${+days} d`);
  if (h) parts.push(`${+h} h`);
  if (min) parts.push(`${+min} min`);
  if (sec && +sec) parts.push(`${+sec} s`);
  return parts.join(' ') || '0 s';
}

/** Event ids are "network:txHash:logIndex" (oci-core model). */
export function parseEventId(id: string): { network: NetworkId; hash: string; logIndex: string } | null {
  const parts = id.split(':');
  if (parts.length < 3) return null;
  return { network: parts[0] as NetworkId, hash: parts.slice(1, -1).join(':'), logIndex: parts[parts.length - 1] };
}

/** Sequence steps are "stepName=eventId@instant". */
export function parseStep(s: string): { name: string; eventId: string; at: string } {
  const eq = s.indexOf('=');
  const at = s.lastIndexOf('@');
  if (eq < 0 || at < eq) return { name: s, eventId: '', at: '' };
  return { name: s.slice(0, eq), eventId: s.slice(eq + 1, at), at: s.slice(at + 1) };
}

/** Position of observed and threshold on a common 0..100 scale, when both are plain numbers. */
export function compareScale(observed: string, threshold: string): { observed: number; threshold: number } | null {
  const o = Number(observed);
  const t = Number(threshold);
  if (!Number.isFinite(o) || !Number.isFinite(t) || o < 0 || t <= 0) return null;
  const max = Math.max(o, t) * 1.2;
  return { observed: (o / max) * 100, threshold: (t / max) * 100 };
}
