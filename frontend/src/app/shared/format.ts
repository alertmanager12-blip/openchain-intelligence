/** Exact decimal formatting of raw integer amounts (base units): BigInt only, never floating point. */
export function formatUnits(raw: string | null | undefined, decimals: number, maxFraction = 6): string {
  if (raw === null || raw === undefined || !/^-?\d+$/.test(raw)) return '—';
  const neg = raw.startsWith('-');
  const digits = (neg ? raw.slice(1) : raw).replace(/^0+(?=\d)/, '');
  const padded = digits.padStart(decimals + 1, '0');
  const int = decimals ? padded.slice(0, -decimals) : padded;
  let frac = decimals ? padded.slice(-decimals) : '';
  frac = frac.slice(0, maxFraction).replace(/0+$/, '');
  const grouped = int.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${neg ? '-' : ''}${grouped}${frac ? '.' + frac : ''}`;
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
