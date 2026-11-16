/** Exact decimal formatting of raw integer amounts (base units): BigInt only, never floating point. */
export function formatUnits(raw: string | null | undefined, decimals: number, maxFraction = 6): string {
  if (raw === null || raw === undefined || !/^-?\d+$/.test(raw)) return '—';
  const neg = raw.startsWith('-');
  const digits = (neg ? raw.slice(1) : raw).replace(/^0+(?=\d)/, '');
  const padded = digits.padStart(decimals + 1, '0');
  const int = decimals ? padded.slice(0, -decimals) : padded;
  const frac = decimals ? padded.slice(-decimals) : '';
  const shown = frac.slice(0, maxFraction).replace(/0+$/, '');
  // A non-zero amount below the displayed precision is never shown as "0": a dust transfer (address poisoning) must
  // stay visible. "<0.01" says "more than nothing, less than the smallest unit shown".
  if (/^0+$/.test(int) && !shown && /[1-9]/.test(frac)) {
    return `${neg ? '-' : ''}<${maxFraction > 0 ? '0.' + '0'.repeat(maxFraction - 1) + '1' : '1'}`;
  }
  const grouped = int.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const zero = /^0+$/.test(int) && !shown;
  return `${neg && !zero ? '-' : ''}${grouped}${shown ? '.' + shown : ''}`;
}


/**
 * Indexing lag of a network in blocks, or null when it is unknown: the server says so (null), or the provider is
 * unhealthy / has no head. Unknown is shown as such, never as 0.
 */
export function indexerLag(x: { indexerLag?: number | null; headBlock: number; finalizedBlock: number; healthy?: boolean }): number | null {
  if (x.indexerLag === null) return null;
  if (x.indexerLag !== undefined) return x.indexerLag;
  if (x.healthy === false || !x.headBlock) return null;
  return x.headBlock - x.finalizedBlock; // older payloads without indexerLag
}

export function shortHash(v: string | null | undefined, head = 6, tail = 4): string {
  if (!v) return '—';
  return v.length <= head + tail + 1 ? v : `${v.slice(0, head)}…${v.slice(-tail)}`;
}

export function timeAgo(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '—';
  const s = Math.round((now - Date.parse(iso)) / 1000);
  if (Number.isNaN(s)) return '—';
  if (s < 60) return `${Math.max(s, 0)}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

export function formatAge(seconds: number): string {
  if (seconds < 3600) return `${Math.max(1, Math.round(seconds / 60))} min`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)} h`;
  return `${Math.round(seconds / 86400)} d`;
}

/** "FALSE_POSITIVE" -> "False positive": enum values are shown in sentence case. */
export function sentence(v: string): string {
  const t = v.replace(/_/g, ' ').toLowerCase();
  return t.charAt(0).toUpperCase() + t.slice(1);
}

const NET_LABELS: Record<string, string> = { ethereum: 'Ethereum', tron: 'TRON', bsc: 'BSC', polygon: 'Polygon', bitcoin: 'Bitcoin', solana: 'Solana' };

/** Display name of a network id ("bsc" -> "BSC"), for option lists and select triggers. */
export function netLabel(n: string): string {
  return NET_LABELS[n] ?? n;
}
