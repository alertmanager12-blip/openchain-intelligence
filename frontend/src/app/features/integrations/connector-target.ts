/** Hides most of a connector target (webhook URLs embed secrets): keeps scheme + host and the last 4 characters. */
export function maskTarget(target: string): string {
  const t = target.trim();
  const m = /^(https?:\/\/[^/]+)(\/.*)?$/i.exec(t);
  if (m) {
    const rest = m[2] ?? '';
    return rest.length > 8 ? `${m[1]}/…${rest.slice(-4)}` : t;
  }
  const at = t.indexOf('@');
  if (at > 0) return `${t[0]}…${t.slice(at)}`;
  return t.length > 8 ? `${t.slice(0, 3)}…${t.slice(-4)}` : t;
}
