import { HttpErrorResponse, HttpEvent, HttpHeaders, HttpRequest, HttpResponse } from '@angular/common/http';
import { Observable, delay, of, throwError } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Alert, AlertStatus, ApiKey, ConnectorType, LabelReport, NetworkId, Page, Rule, RuleTestRequest, Webhook } from '../models';
import { AVAILABLE_EVENT_TYPES, DEFAULT_FIELDS, EVENT_TYPES } from '../rules/rule-model';
import { yamlToRule } from '../rules/rule-yaml';
import { MockDb, ProblemError } from './mock-db';

let db: MockDb | null = null;
/** Lazily created so tests and real-backend sessions never pay for it. */
export function mockDb(): MockDb {
  return (db ??= new MockDb());
}

type Handler = (req: HttpRequest<unknown>, m: RegExpExecArray) => unknown;
const routes: [string, RegExp, Handler][] = [];
const on = (method: string, pattern: string, h: Handler) => routes.push([method, new RegExp(`^${pattern}$`), h]);

const net = (req: HttpRequest<unknown>): NetworkId => (req.params.get('network') as NetworkId) || 'ethereum';
const body = <T>(req: HttpRequest<unknown>) => (req.body ?? {}) as T;
const page = <T>(all: T[], req: HttpRequest<unknown>, size = 25): Page<T> => {
  const offset = Number(req.params.get('cursor') ?? 0);
  const limit = Number(req.params.get('limit') ?? size);
  const items = all.slice(offset, offset + limit);
  return { items, nextCursor: offset + limit < all.length ? String(offset + limit) : null };
};
const now = () => new Date().toISOString();
const ME = 'you@example.invalid';

function findAlert(id: string): Alert {
  const a = mockDb().alerts.find((x) => x.id === id);
  if (!a) throw new ProblemError(404, 'Not found', `Alert ${id} does not exist`);
  return a;
}
function touch(a: Alert, action: string, detail: string | null = null): Alert {
  a.updatedAt = now();
  a.history.unshift({ at: a.updatedAt, actor: ME, action, detail });
  return structuredClone(a);
}
const TRANSITIONS: Record<AlertStatus, AlertStatus[]> = {
  OPEN: ['ACKNOWLEDGED', 'INVESTIGATING', 'RESOLVED', 'FALSE_POSITIVE'],
  ACKNOWLEDGED: ['INVESTIGATING', 'RESOLVED', 'FALSE_POSITIVE'],
  INVESTIGATING: ['RESOLVED', 'FALSE_POSITIVE'],
  RESOLVED: ['OPEN'],
  FALSE_POSITIVE: ['OPEN'],
};
function transition(a: Alert, to: AlertStatus, reason?: string): Alert {
  if (!TRANSITIONS[a.status].includes(to)) throw new ProblemError(409, 'Invalid transition', `Cannot move an alert from ${a.status} to ${to}`);
  a.status = to;
  return touch(a, to, reason ?? null);
}
function saveRule(yaml: string, existing?: Rule): Rule {
  const r = yamlToRule(yaml);
  if (!r.draft || r.errors.length) throw Object.assign(new ProblemError(422, 'Rule invalid', `${r.errors.length} validation error(s)`), { errors: r.errors.map((e) => ({ field: e.path, message: e.message })) });
  const d = r.draft;
  const rules = mockDb().rules;
  if (!existing && rules.some((x) => x.id === d.id)) throw new ProblemError(409, 'Conflict', `A rule with id "${d.id}" already exists`);
  const saved: Rule = { id: d.id, name: d.name, enabled: d.enabled, severity: d.severity, yaml, version: (existing?.version ?? 0) + 1, updatedAt: now() };
  if (existing) rules.splice(rules.indexOf(existing), 1, saved);
  else rules.unshift(saved);
  return saved;
}
function findRule(id: string): Rule {
  const r = mockDb().rules.find((x) => x.id === id);
  if (!r) throw new ProblemError(404, 'Not found', `Rule ${id} does not exist`);
  return r;
}

on('GET', '/dashboard', () => mockDb().dashboard());
on('GET', '/networks', (req) => mockDb().networks(req.params.get('all') === 'true'));
on('GET', '/signals', (req) => page(mockDb().signals, req, 20));
on('GET', '/wallets/([^/]+)/analysis', (req, m) => mockDb().wallet(decodeURIComponent(m[1]), net(req)));
on('GET', '/wallets/([^/]+)/transactions', (req, m) => mockDb().transactions(decodeURIComponent(m[1]), net(req), req.params.get('cursor'), Number(req.params.get('limit') ?? 25)));
on('GET', '/wallets/([^/]+)/risk', (req, m) => mockDb().risk(decodeURIComponent(m[1]), net(req)));
on('GET', '/wallets/([^/]+)/exposure', (req, m) => mockDb().exposure(decodeURIComponent(m[1]), net(req)));
on('GET', '/transactions/([^/]+)/analysis', (req, m) => mockDb().transaction(decodeURIComponent(m[1]), net(req)));
on('GET', '/graph/([^/]+)', (req, m) => mockDb().graph(decodeURIComponent(m[1]), net(req), Math.min(3, Math.max(1, Number(req.params.get('depth') ?? 1))), req.params.get('token')));

on('GET', '/rules/fields', () => DEFAULT_FIELDS);
on('GET', '/rules/event-types', () => EVENT_TYPES.map((type) => ({ type, available: AVAILABLE_EVENT_TYPES.includes(type) })));
on('GET', '/rules/export', () => mockDb().rules.map((r) => r.yaml).join('---\n'));
on('GET', '/rules/noise', (req) => {
  const hours = Number(req.params.get('hours') ?? 24);
  const since = Date.now() - hours * 3_600_000;
  const byRule = new Map<string, { total: number; open: number; falsePositive: number; resolved: number; recent: number }>();
  for (const a of mockDb().alerts) {
    const s = byRule.get(a.ruleId) ?? { total: 0, open: 0, falsePositive: 0, resolved: 0, recent: 0 };
    s.total++;
    if (a.status === 'FALSE_POSITIVE') s.falsePositive++;
    else if (a.status === 'RESOLVED') s.resolved++;
    else s.open++;
    if (Date.parse(a.createdAt) >= since) s.recent++;
    byRule.set(a.ruleId, s);
  }
  return [...byRule.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([ruleId, s]) => ({
    ruleId, ...s, windowHours: hours, falsePositiveRate: s.falsePositive + s.resolved === 0 ? null : s.falsePositive / (s.falsePositive + s.resolved),
  }));
});
on('POST', '/rules/validate', (req) => {
  const r = yamlToRule(body<{ yaml: string }>(req).yaml ?? '');
  return { valid: r.errors.length === 0, errors: r.errors };
});
on('POST', '/rules/import', (req) => String(req.body ?? '').split(/^---\s*$/m).filter((s) => s.trim()).map((y) => {
  const id = yamlToRule(y).draft?.id;
  const existing = mockDb().rules.find((x) => x.id === id);
  return saveRule(y.trim() + '\n', existing);
}));
on('POST', '/rules/test', (req) => {
  const b = body<RuleTestRequest>(req);
  return mockDb().simulate(b.yaml ?? '', b.from, b.to);
});
on('POST', '/rules/([^/]+)/test', (req, m) => {
  const b = body<RuleTestRequest>(req);
  return mockDb().simulate(findRule(decodeURIComponent(m[1])).yaml, b.from, b.to);
});
on('POST', '/rules/([^/]+)/(enable|disable)', (_req, m) => {
  const r = findRule(decodeURIComponent(m[1]));
  r.enabled = m[2] === 'enable';
  r.yaml = r.yaml.replace(/^enabled: (true|false)$/m, `enabled: ${r.enabled}`);
  r.updatedAt = now();
  return r;
});
on('GET', '/rules', (req) => page(mockDb().rules, req, 100));
on('POST', '/rules', (req) => saveRule(body<{ yaml: string }>(req).yaml));
on('GET', '/rules/([^/]+)', (_req, m) => findRule(decodeURIComponent(m[1])));
on('PUT', '/rules/([^/]+)', (req, m) => saveRule(body<{ yaml: string }>(req).yaml, findRule(decodeURIComponent(m[1]))));
on('DELETE', '/rules/([^/]+)', (_req, m) => {
  const rules = mockDb().rules;
  rules.splice(rules.indexOf(findRule(decodeURIComponent(m[1]))), 1);
  return null;
});

on('GET', '/alerts', (req) => {
  const st = req.params.get('status');
  const sev = req.params.get('severity');
  return page(mockDb().alerts.filter((a) => (!st || a.status === st) && (!sev || a.severity === sev)), req, 100);
});
on('GET', '/alerts/([^/]+)', (_req, m) => structuredClone(findAlert(m[1])));
on('POST', '/alerts/([^/]+)/acknowledge', (_req, m) => transition(findAlert(m[1]), 'ACKNOWLEDGED'));
on('POST', '/alerts/([^/]+)/status', (req, m) => {
  const b = body<{ status: AlertStatus; reason?: string }>(req);
  return transition(findAlert(m[1]), b.status, b.reason);
});
on('POST', '/alerts/([^/]+)/assign', (req, m) => {
  const a = findAlert(m[1]);
  a.assignee = body<{ assignee: string | null }>(req).assignee || null;
  return touch(a, 'ASSIGNED', a.assignee ?? 'unassigned');
});
on('POST', '/alerts/([^/]+)/comments', (req, m) => {
  const a = findAlert(m[1]);
  const text = (body<{ body: string }>(req).body ?? '').trim();
  if (!text) throw new ProblemError(400, 'Invalid request', 'Comment body must not be empty');
  a.comments.push({ id: mockDb().nextId('cm'), author: ME, body: text, createdAt: now() });
  return touch(a, 'COMMENTED');
});

on('GET', '/webhooks', () => mockDb().webhooks);
on('POST', '/webhooks', (req) => {
  const b = body<{ type: ConnectorType; name: string; target: string }>(req);
  if (!b.name?.trim() || !b.target?.trim()) throw Object.assign(new ProblemError(422, 'Validation failed', 'name and target are required'), { errors: [{ field: 'target', message: 'required' }] });
  if (['WEBHOOK', 'SLACK', 'DISCORD'].includes(b.type) && !/^https:\/\//.test(b.target)) throw Object.assign(new ProblemError(422, 'Validation failed', 'Endpoints must use https'), { errors: [{ field: 'target', message: 'must start with https://' }] });
  const w: Webhook = { id: mockDb().nextId('wh'), type: b.type, name: b.name, target: b.target, enabled: true, createdAt: now(), lastDeliveryAt: null, lastDeliveryStatus: null };
  mockDb().webhooks.push(w);
  const secret = 'whsec_demo_' + Array.from(crypto.getRandomValues(new Uint8Array(18)), (x) => x.toString(16).padStart(2, '0')).join('');
  return { ...w, secret: b.type === 'WEBHOOK' ? secret : undefined };
});
on('DELETE', '/webhooks/([^/]+)', (_req, m) => {
  const list = mockDb().webhooks;
  const i = list.findIndex((w) => w.id === m[1]);
  if (i < 0) throw new ProblemError(404, 'Not found', 'Unknown connector');
  list.splice(i, 1);
  return null;
});
on('POST', '/webhooks/([^/]+)/test', (_req, m) => {
  const w = mockDb().webhooks.find((x) => x.id === m[1]);
  if (!w) throw new ProblemError(404, 'Not found', 'Unknown connector');
  const ok = w.lastDeliveryStatus !== 'FAILED';
  w.lastDeliveryAt = now();
  w.lastDeliveryStatus = ok ? 'OK' : 'FAILED';
  return ok ? { delivered: true, statusCode: 200, message: 'Test event delivered (demo)' } : { delivered: false, statusCode: 410, message: 'Endpoint answered 410 Gone (demo). Check the URL.' };
});

on('GET', '/labels/reports', (req) => page(mockDb().reports, req));
on('POST', '/labels/reports', (req) => {
  const r = body<LabelReport>(req);
  if (!r.address || !r.description || r.description.length < 20) throw Object.assign(new ProblemError(422, 'Validation failed', 'A report needs an address and a description of at least 20 characters'), { errors: [{ field: 'description', message: 'min 20 characters' }] });
  const saved = { ...r, id: mockDb().nextId('rep'), status: 'PENDING' as const, createdAt: now() };
  mockDb().reports.unshift(saved);
  return saved;
});
on('GET', '/labels', (req) => {
  const a = req.params.get('address');
  const n = req.params.get('network');
  return page(mockDb().labels.filter((l) => (!a || l.address === a) && (!n || l.network === n)), req, 100);
});

on('GET', '/users', () => mockDb().users);
on('PATCH', '/users/([^/]+)', (req, m) => {
  const u = mockDb().users.find((x) => x.id === m[1]);
  if (!u) throw new ProblemError(404, 'Not found', 'Unknown user');
  u.role = body<{ role: typeof u.role }>(req).role;
  return u;
});
on('GET', '/api-keys', () => mockDb().apiKeys);
on('POST', '/api-keys', (req) => {
  const b = body<{ name: string; scopes: string[] }>(req);
  if (!b.name?.trim()) throw new ProblemError(422, 'Validation failed', 'name is required');
  const hex = Array.from(crypto.getRandomValues(new Uint8Array(16)), (x) => x.toString(16).padStart(2, '0')).join('');
  const k: ApiKey = { id: mockDb().nextId('k'), name: b.name, prefix: `oci_demo_${hex.slice(0, 4)}`, scopes: b.scopes, createdAt: now(), lastUsedAt: null, revoked: false };
  mockDb().apiKeys.unshift(k);
  return { ...k, secret: `oci_demo_${hex}` };
});
on('POST', '/api-keys/([^/]+)/revoke', (_req, m) => {
  const k = mockDb().apiKeys.find((x) => x.id === m[1]);
  if (!k) throw new ProblemError(404, 'Not found', 'Unknown key');
  k.revoked = true;
  return k;
});

const problemHeaders = new HttpHeaders({ 'Content-Type': 'application/problem+json' });

/** Answers one /api/v1 request from the in-memory MockDb. Errors are real problem+json responses. */
export function handleMock(req: HttpRequest<unknown>): Observable<HttpEvent<unknown>> {
  const path = req.url.slice(environment.apiBaseUrl.length).split('?')[0];
  const latency = 150 + Math.floor(Math.random() * 250);
  for (const [method, re, h] of routes) {
    const m = re.exec(path);
    if (!m || method !== req.method) continue;
    try {
      const result = h(req, m);
      return of(new HttpResponse({ status: result === null ? 204 : 200, body: structuredClone(result) })).pipe(delay(latency));
    } catch (e) {
      if (!(e instanceof ProblemError)) throw e;
      const problem = { type: 'about:blank', title: e.title, status: e.status, detail: e.detail, instance: req.url, errors: (e as ProblemError & { errors?: unknown }).errors };
      return throwError(() => new HttpErrorResponse({ status: e.status, statusText: e.title, error: problem, headers: problemHeaders, url: req.url }));
    }
  }
  return throwError(() => new HttpErrorResponse({ status: 404, error: { title: 'Not found', status: 404, detail: `Mock mode: no handler for ${req.method} ${path}` }, headers: problemHeaders }));
}
