/* In-memory fake data for mock mode. Every address and hash here is fictitious ("fa4ede30" ≈ fake demo). */
import {
  ALERT_STATUSES, Alert, AlertStatus, ApiKey, Balance, Dashboard, ChainTransaction, Counterparty, EvidenceLevel, Exposure, Graph, GraphEdge, GraphNode, Label,
  LabelReport, NETWORKS, NetworkId, NetworkStatus, RiskContribution, RiskLevel, RiskScore, Rule, RuleTestResult, SEVERITIES, Severity,
  Signal, Token, TransactionAnalysis, TokenTransfer, User, WalletAnalysis, Webhook,
} from '../models';
import { RuleDraft, emptyRule, newCondition, newGroup } from '../rules/rule-model';
import { ruleToYaml, yamlToRule } from '../rules/rule-yaml';

// ---------------------------------------------------------------- deterministic randomness

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
}

export class Rng {
  private s: number;
  constructor(seed: string | number) {
    this.s = typeof seed === 'number' ? seed >>> 0 || 1 : hash(seed) || 1;
  }
  next(): number {
    // mulberry32
    let t = (this.s += 0x6d2b79f5);
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  }
  int(min: number, max: number): number {
    return min + Math.floor(this.next() * (max - min + 1));
  }
  pick<T>(a: readonly T[]): T {
    return a[Math.floor(this.next() * a.length)];
  }
  hex(n: number): string {
    let out = '';
    for (let i = 0; i < n; i++) out += '0123456789abcdef'[this.int(0, 15)];
    return out;
  }
  /** Random raw amount with up to `whole` integer digits, as an exact integer string. */
  raw(decimals: number, whole: number): string {
    let d = String(this.int(1, 9));
    for (let i = 1; i < this.int(1, whole); i++) d += this.int(0, 9);
    for (let i = 0; i < decimals; i++) d += this.int(0, 9);
    return d.replace(/^0+(?=\d)/, '');
  }
}

const B58 = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
const NOW = Date.now();
const iso = (msAgo: number) => new Date(NOW - msAgo).toISOString();
const H = 3_600_000;
const D = 24 * H;

export function fakeAddress(network: NetworkId, i: number): string {
  switch (network) {
    case 'tron':
      return 'TFakeDemoAccountAddressXXXXXXXXX' + B58[Math.floor(i / 58) % 58] + B58[i % 58];
    case 'bitcoin':
      return 'bc1qfakedem' + 'q'.repeat(20) + (i % 10 === 1 ? 'x' : 'z') + 'p'.repeat(8);
    case 'solana':
      return 'FakeDemoSo1anaAccountXXXXXXXXXXXXXXXXX' + B58[i % 58] + B58[(i * 7) % 58];
    default:
      return '0xfa4ede30' + i.toString(16).padStart(32, '0');
  }
}

export function fakeTxHash(network: NetworkId, rng: Rng): string {
  if (network === 'solana') return 'FakeDemoSig' + Array.from({ length: 77 }, () => B58[rng.int(0, 57)]).join('');
  const body = 'fa4ede30' + rng.hex(56);
  return network === 'tron' || network === 'bitcoin' ? body : '0x' + body;
}

const TOKENS: Record<NetworkId, Token[]> = {
  ethereum: [
    { network: 'ethereum', contract: null, symbol: 'ETH', decimals: 18 },
    { network: 'ethereum', contract: '0xfa4ede30000000000000000000000000000c0001', symbol: 'USDT', decimals: 6 },
    { network: 'ethereum', contract: '0xfa4ede30000000000000000000000000000c0002', symbol: 'USDC', decimals: 6 },
  ],
  tron: [
    { network: 'tron', contract: null, symbol: 'TRX', decimals: 6 },
    { network: 'tron', contract: 'TFakeDemoAccountAddressXXXXXXXXXzz', symbol: 'USDT', decimals: 6 },
  ],
  bsc: [
    { network: 'bsc', contract: null, symbol: 'BNB', decimals: 18 },
    { network: 'bsc', contract: '0xfa4ede30000000000000000000000000000c0003', symbol: 'USDT', decimals: 18 },
  ],
  polygon: [
    { network: 'polygon', contract: null, symbol: 'POL', decimals: 18 },
    { network: 'polygon', contract: '0xfa4ede30000000000000000000000000000c0004', symbol: 'USDC', decimals: 6 },
  ],
  bitcoin: [{ network: 'bitcoin', contract: null, symbol: 'BTC', decimals: 8 }],
  solana: [
    { network: 'solana', contract: null, symbol: 'SOL', decimals: 9 },
    { network: 'solana', contract: 'FakeDemoSo1anaAccountXXXXXXXXXXXXXXXXXzz', symbol: 'USDC', decimals: 6 },
  ],
};
export const tokensOf = (n: NetworkId) => TOKENS[n];

const LABEL_CATEGORIES = ['exchange', 'mixer', 'scam-report', 'sanctioned', 'bridge', 'defi-protocol', 'gambling'];
const SIGNAL_TYPES: { type: string; ev: EvidenceLevel; text: string }[] = [
  { type: 'UNUSUAL_AMOUNT', ev: 'ANOMALY', text: 'Amount is 14x above the 90-day median of this wallet' },
  { type: 'FAST_FORWARDING', ev: 'ANOMALY', text: '92% of received funds forwarded within 4 minutes' },
  { type: 'STRUCTURING', ev: 'RISK_SIGNAL', text: '11 transfers just below 10,000 USDT within 2 hours' },
  { type: 'DORMANT_REACTIVATION', ev: 'ANOMALY', text: 'Wallet inactive for 412 days then moved its full balance' },
  { type: 'MIXER_EXPOSURE', ev: 'RISK_SIGNAL', text: 'Received funds 2 hops from an address labelled "mixer" by a community source' },
  { type: 'SANCTIONED_EXPOSURE', ev: 'CONFIRMED_BY_SOURCE', text: 'Direct counterparty appears on a cited sanctions list (demo source)' },
  { type: 'CIRCULAR_FLOW', ev: 'RISK_SIGNAL', text: 'Funds returned to origin through 3 intermediate wallets' },
  { type: 'UNUSUAL_APPROVAL', ev: 'ANOMALY', text: 'Unlimited token approval granted to a contract deployed 3 hours ago' },
  { type: 'RAPID_DRAIN', ev: 'RISK_SIGNAL', text: 'Balance dropped 98% in one block across 6 recipients' },
];

const RISK_FOR = (score: number): RiskLevel => (score >= 85 ? 'CRITICAL' : score >= 65 ? 'HIGH' : score >= 35 ? 'MEDIUM' : 'LOW');

export class ProblemError {
  constructor(readonly status: number, readonly title: string, readonly detail: string) {}
}

// ---------------------------------------------------------------- the store

export class MockDb {
  readonly alerts: Alert[] = [];
  readonly rules: Rule[] = [];
  readonly webhooks: Webhook[] = [];
  readonly labels: Label[] = [];
  readonly reports: LabelReport[] = [];
  readonly users: User[] = [];
  readonly apiKeys: ApiKey[] = [];
  readonly signals: Signal[] = [];
  private idSeq = 1000;

  constructor() {
    const rng = new Rng(42);
    for (let i = 0; i < 36; i++) this.signals.push(this.makeSignal(rng, i));
    this.signals.sort((a, b) => b.detectedAt.localeCompare(a.detectedAt));
    this.seedRules();
    this.seedAlerts(rng);
    this.webhooks.push(
      { id: 'wh-ops', type: 'WEBHOOK', name: 'SOC webhook', target: 'https://hooks.example.invalid/oci', enabled: true, createdAt: iso(20 * D), lastDeliveryAt: iso(2 * H), lastDeliveryStatus: 'OK' },
      { id: 'wh-slack', type: 'SLACK', name: '#compliance-alerts', target: 'https://hooks.slack.example.invalid/T000/B000', enabled: true, createdAt: iso(12 * D), lastDeliveryAt: iso(5 * H), lastDeliveryStatus: 'FAILED' },
    );
    NETWORKS.slice(0, 4).forEach((n, ni) => {
      for (let i = 1; i <= 5; i++) {
        const cat = LABEL_CATEGORIES[(i + ni) % LABEL_CATEGORIES.length];
        this.labels.push({
          id: `lbl_${n}_${i}`, address: fakeAddress(n, i), network: n, category: cat, name: `Demo ${cat} ${i}`,
          source: cat === 'sanctioned' ? 'demo-official-list' : i % 2 ? 'community' : 'oci-curated',
          reliability: cat === 'sanctioned' ? 0.99 : i % 2 ? 0.55 : 0.85,
          evidenceLevel: cat === 'sanctioned' ? 'CONFIRMED_BY_SOURCE' : cat === 'scam-report' || cat === 'mixer' ? 'RISK_SIGNAL' : 'ANOMALY',
          createdAt: iso((i + ni) * 3 * D),
        });
      }
    });
    this.reports.push({ id: 'rep_1', address: fakeAddress('tron', 9), network: 'tron', category: 'scam-report', description: 'Fake support account asking users for seed phrases (demo).', reference: 'https://example.invalid/report/1', status: 'PENDING', createdAt: iso(D) });
    this.users.push(
      { id: 'u1', email: 'admin@example.invalid', name: 'Demo Admin', role: 'ADMIN', lastLoginAt: iso(H) },
      { id: 'u2', email: 'analyst@example.invalid', name: 'Demo Analyst', role: 'ANALYST', lastLoginAt: iso(3 * H) },
      { id: 'u3', email: 'viewer@example.invalid', name: 'Demo Viewer', role: 'VIEWER', lastLoginAt: null },
    );
    this.apiKeys.push({ id: 'k1', name: 'CI pipeline', prefix: 'oci_demo_7f3a', scopes: ['rules:read', 'alerts:read'], createdAt: iso(30 * D), lastUsedAt: iso(H), revoked: false });
  }

  nextId(prefix: string): string {
    return `${prefix}_${++this.idSeq}`;
  }

  private makeSignal(rng: Rng, i: number): Signal {
    const n = rng.pick(['ethereum', 'tron', 'bsc', 'polygon'] as NetworkId[]);
    const t = rng.pick(SIGNAL_TYPES);
    const sev: Severity = t.ev === 'CONFIRMED_BY_SOURCE' ? 'CRITICAL' : rng.pick(['LOW', 'MEDIUM', 'MEDIUM', 'HIGH'] as Severity[]);
    return {
      id: `sig_${i + 1}`, type: t.type, address: fakeAddress(n, rng.int(1, 40)), network: n, txHash: fakeTxHash(n, rng),
      severity: sev, confidence: Math.round((0.45 + rng.next() * 0.5) * 100) / 100, evidenceLevel: t.ev,
      evidence: [{ kind: 'observation', description: t.text, reference: null, source: t.ev === 'CONFIRMED_BY_SOURCE' ? 'demo-official-list' : 'oci-detector', observedAt: iso(rng.int(1, 72) * H) }],
      detectorId: `${t.type.toLowerCase().replace(/_/g, '-')}@1`, detectedAt: iso(rng.int(1, 72 * 60) * 60_000),
    };
  }

  private seedRules(): void {
    const drafts: RuleDraft[] = [
      { ...emptyRule(), id: 'large-stablecoin-transfer', name: 'Large stablecoin transfer', description: 'Single USDT/USDC transfer of 250k or more', severity: 'HIGH', networks: ['ethereum', 'tron'],
        when: newGroup('all', [newCondition('transaction.token.symbol', 'in', ['USDT', 'USDC']), newCondition('transaction.amount', 'gte', '250000')]), actions: [{ type: 'alert', target: '' }, { type: 'webhook', target: 'wh-ops' }] },
      { ...emptyRule(), id: 'mixer-exposure', name: 'Funds received from mixer-labelled address', severity: 'CRITICAL', networks: ['ethereum', 'bsc', 'polygon'],
        when: newGroup('all', [newCondition('counterparty.labels', 'contains', 'mixer'), newGroup('any', [newCondition('transaction.amount_usd', 'gte', '10000'), newCondition('counterparty.risk_score', 'gte', 65)])]), evidenceLevel: 'RISK_SIGNAL', cooldown: '1h' },
      { ...emptyRule(), id: 'structuring-burst', name: 'Structuring burst (many sub-10k transfers)', severity: 'MEDIUM', networks: ['tron'],
        when: newGroup('all', [newCondition('transaction.token.symbol', 'eq', 'USDT'), newCondition('transaction.amount', 'between', ['9000', '9999.99'])]),
        window: { duration: '2h', aggregate: 'count', field: null, groupBy: ['transaction.from'], op: 'gte', value: '8' } },
      { ...emptyRule(), id: 'risky-approval', name: 'Unlimited approval to new contract', severity: 'LOW', enabled: false, eventTypes: ['APPROVAL'],
        when: newGroup('all', [newCondition('approval.unlimited', 'eq', true), newCondition('approval.spender_age_days', 'lt', 7)]) },
    ];
    drafts.forEach((d, i) => this.rules.push({ id: d.id, name: d.name, enabled: d.enabled, severity: d.severity, yaml: ruleToYaml(d), version: i + 1, updatedAt: iso((i + 1) * D) }));
  }

  private seedAlerts(rng: Rng): void {
    const statuses: AlertStatus[] = ['OPEN', 'OPEN', 'OPEN', 'ACKNOWLEDGED', 'INVESTIGATING', 'RESOLVED', 'FALSE_POSITIVE'];
    for (let i = 0; i < 42; i++) {
      const rule = rng.pick(this.rules);
      const n = rng.pick(['ethereum', 'tron', 'bsc'] as NetworkId[]);
      const tok = rng.pick(tokensOf(n));
      const status = rng.pick(statuses);
      const created = rng.int(10, 7 * 24 * 60) * 60_000;
      const sev = i % 9 === 0 ? 'CRITICAL' : rule.severity;
      const address = fakeAddress(n, rng.int(1, 40));
      const history: Alert['history'] = [{ at: iso(created), actor: 'system', action: 'CREATED', detail: `Rule ${rule.id} v${rule.version} matched` }];
      if (status !== 'OPEN') history.push({ at: iso(created - 20 * 60_000), actor: 'analyst@example.invalid', action: status, detail: null });
      this.alerts.push({
        id: `al_${String(i + 1).padStart(4, '0')}`, ruleId: rule.id, ruleName: rule.name, severity: sev, status, network: n, address, txHash: fakeTxHash(n, rng),
        summary: `${rule.name}: ${tok.symbol} transfer from ${address.slice(0, 12)}…`, evidenceLevel: rule.id === 'mixer-exposure' ? 'RISK_SIGNAL' : 'ANOMALY',
        assignee: status === 'INVESTIGATING' ? 'analyst@example.invalid' : null, createdAt: iso(created), updatedAt: iso(created - 20 * 60_000),
        matchedEvent: { type: 'TOKEN_TRANSFER', network: n, from: address, to: fakeAddress(n, rng.int(41, 80)), 'token.symbol': tok.symbol, amount: rng.raw(0, 6) },
        comments: [], history: history.reverse(),
      });
    }
    this.alerts.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }

  // ---------------------------------------------------------------- read models

  networks(): NetworkStatus[] {
    const base: Record<NetworkId, number> = { ethereum: 21_934_120, tron: 71_204_118, bsc: 46_118_430, polygon: 69_502_331, bitcoin: 921_404, solana: 334_120_998 };
    return NETWORKS.map((n, i) => ({
      network: n, headBlock: base[n], finalizedBlock: base[n] - [64, 19, 15, 128, 6, 32][i], healthy: n !== 'solana',
      providerId: n === 'solana' ? 'solana-secondary' : `${n}-primary`, latencyMillis: [180, 95, 140, 210, 420, 2900][i], checkedAt: iso(15_000),
      detail: n === 'solana' ? 'Primary provider timing out; failed over to secondary. Data may lag.' : null,
    }));
  }

  private assertReachable(network: NetworkId): void {
    if (network === 'solana') throw new ProblemError(503, 'Provider unavailable', 'All Solana providers are currently failing. Results are temporarily unavailable; retry in a minute.');
  }

  risk(address: string, network: NetworkId): RiskScore {
    this.assertReachable(network);
    const rng = new Rng(`risk:${network}:${address}`);
    const label = this.labels.find((l) => l.address === address && l.network === network);
    const factors: Omit<RiskContribution, 'points'>[] = [];
    const cands: [string, string, EvidenceLevel, string][] = [
      ['MIXER_EXPOSURE', 'oci-detector', 'RISK_SIGNAL', '14% of inbound volume within 2 hops of addresses labelled "mixer" by community sources'],
      ['FAST_FORWARDING', 'oci-detector', 'ANOMALY', 'Median holding time of received funds is 3 minutes (population median: 2 days)'],
      ['STRUCTURING', 'oci-detector', 'RISK_SIGNAL', 'Repeated transfers just below common reporting thresholds'],
      ['COUNTERPARTY_EXCHANGE', 'oci-curated', 'ANOMALY', 'Most outflows go to labelled exchange deposit addresses (lowers uncertainty)'],
      ['NEW_WALLET', 'oci-detector', 'ANOMALY', 'Wallet first seen less than 7 days ago'],
    ];
    const k = rng.int(2, cands.length);
    for (const [factor, source, ev, explanation] of cands.slice(0, k))
      factors.push({ factor, signalId: `sig_${rng.int(1, 36)}`, source, weight: Math.round((0.1 + rng.next() * 0.4) * 100) / 100, confidence: Math.round((0.4 + rng.next() * 0.55) * 100) / 100, ageSeconds: rng.int(600, 30 * 86400), explanation, evidenceLevel: ev });
    if (label?.evidenceLevel === 'CONFIRMED_BY_SOURCE')
      factors.unshift({ factor: 'SANCTIONED_LIST', signalId: null, source: label.source, weight: 1, confidence: label.reliability, ageSeconds: 86400 * 3, explanation: `Listed by ${label.source} (demo). Confirmed by source, see reference.`, evidenceLevel: 'CONFIRMED_BY_SOURCE' });
    const contributions = factors.map((f) => ({ ...f, points: Math.round(f.weight * f.confidence * 100 * (f.factor === 'COUNTERPARTY_EXCHANGE' ? -0.3 : 1)) }));
    const score = Math.max(0, Math.min(100, contributions.reduce((s, c) => s + c.points, 0)));
    const history = Array.from({ length: 12 }, (_, i) => {
      const s = Math.max(0, Math.min(100, score + rng.int(-15, 8) * (i === 11 ? 0 : 1)));
      return { at: iso((11 - i) * 5 * D), score: i === 11 ? score : s, level: RISK_FOR(i === 11 ? score : s) };
    });
    return { address, network, score, level: RISK_FOR(score), contributions, method: 'weighted-evidence', modelVersion: 'demo-1.0', computedAt: iso(5 * 60_000), history };
  }

  wallet(address: string, network: NetworkId): WalletAnalysis {
    this.assertReachable(network);
    const rng = new Rng(`wallet:${network}:${address}`);
    const tokens = tokensOf(network);
    const balances: Balance[] = tokens.map((t) => ({ token: t, rawAmount: rng.raw(t.decimals, 5), atBlock: this.networks().find((s) => s.network === network)!.headBlock, observedAt: iso(30_000) }));
    const counterparties: Counterparty[] = Array.from({ length: rng.int(6, 14) }, () => {
      const t = rng.pick(tokens);
      const a = fakeAddress(network, rng.int(1, 60));
      const lbl = this.labels.find((l) => l.address === a && l.network === network);
      const dir = rng.pick(['IN', 'OUT', 'BOTH'] as const);
      return { address: a, label: lbl?.name ?? null, direction: dir, txCount: rng.int(1, 80), token: t, rawIn: dir === 'OUT' ? '0' : rng.raw(t.decimals, 6), rawOut: dir === 'IN' ? '0' : rng.raw(t.decimals, 6), riskLevel: rng.pick(['LOW', 'LOW', 'MEDIUM', 'HIGH', 'UNKNOWN'] as RiskLevel[]) };
    });
    const risk = this.risk(address, network);
    return {
      address, network, firstSeen: iso(rng.int(10, 900) * D), lastSeen: iso(rng.int(1, 48) * H), txCount: rng.int(12, 4000), isContract: rng.next() < 0.15,
      labels: this.labels.filter((l) => l.address === address && l.network === network), balances,
      flows: tokens.map((t) => ({ token: t, rawIn: rng.raw(t.decimals, 7), rawOut: rng.raw(t.decimals, 7) })), counterparties,
      activity: Array.from({ length: 30 }, (_, i) => ({ day: iso((29 - i) * D).slice(0, 10), txIn: rng.int(0, 25), txOut: rng.int(0, 25) })),
      signals: this.signals.slice(0, rng.int(1, 5)).map((s) => ({ ...s, address, network })), risk,
    };
  }

  transactions(address: string, network: NetworkId, cursor: string | null, limit: number): { items: ChainTransaction[]; nextCursor: string | null } {
    this.assertReachable(network);
    const offset = cursor ? Number(cursor) : 0;
    const total = 60;
    const native = tokensOf(network)[0];
    const items = Array.from({ length: Math.min(limit, total - offset) }, (_, k) => {
      const i = offset + k;
      const rng = new Rng(`tx:${network}:${address}:${i}`);
      const out = rng.next() < 0.5;
      const other = fakeAddress(network, rng.int(1, 60));
      return {
        network, hash: fakeTxHash(network, rng), blockNumber: 1_000_000 - i * 17, timestamp: iso(i * 3 * H + rng.int(0, H)),
        from: out ? address : other, to: out ? other : address, value: rng.raw(native.decimals, 3), fee: rng.raw(native.decimals - 4 > 0 ? native.decimals - 4 : 0, 1),
        success: rng.next() > 0.05, methodSelector: rng.next() < 0.5 ? '0xa9059cbb' : null, contractInteraction: rng.next() < 0.5, nativeSymbol: native.symbol, nativeDecimals: native.decimals,
      };
    });
    return { items, nextCursor: offset + items.length < total ? String(offset + items.length) : null };
  }

  exposure(address: string, network: NetworkId): Exposure {
    this.assertReachable(network);
    const rng = new Rng(`exp:${network}:${address}`);
    const entry = (hops: number) => {
      const cat = rng.pick(LABEL_CATEGORIES);
      return { category: cat, hops, share: Math.round(rng.next() * 30) / 100, counterparty: fakeAddress(network, rng.int(1, 60)), evidenceLevel: (cat === 'sanctioned' ? 'CONFIRMED_BY_SOURCE' : cat === 'mixer' || cat === 'scam-report' ? 'RISK_SIGNAL' : 'ANOMALY') as EvidenceLevel };
    };
    return { address, network, direct: Array.from({ length: rng.int(1, 4) }, () => entry(1)), indirect: Array.from({ length: rng.int(1, 5) }, () => entry(rng.int(2, 3))) };
  }

  transaction(hash: string, network: NetworkId): TransactionAnalysis {
    this.assertReachable(network);
    const rng = new Rng(`txa:${network}:${hash}`);
    const native = tokensOf(network)[0];
    const from = fakeAddress(network, rng.int(1, 30));
    const to = fakeAddress(network, rng.int(31, 60));
    const tok = rng.pick(tokensOf(network));
    const transfers: TokenTransfer[] = tok.contract ? [{ network, txHash: hash, logIndex: 0, blockNumber: 1_000_123, timestamp: iso(2 * H), from, to, token: tok, rawAmount: rng.raw(tok.decimals, 6) }] : [];
    return {
      transaction: { network, hash, blockNumber: 1_000_123, timestamp: iso(2 * H), from, to: tok.contract ?? to, value: tok.contract ? '0' : rng.raw(native.decimals, 3), fee: rng.raw(Math.max(native.decimals - 4, 0), 1), success: true, methodSelector: tok.contract ? '0xa9059cbb' : null, contractInteraction: !!tok.contract, nativeSymbol: native.symbol, nativeDecimals: native.decimals },
      transfers, signals: rng.next() < 0.6 ? [{ ...this.signals[rng.int(0, this.signals.length - 1)], txHash: hash, network, address: from }] : [], confirmations: rng.int(20, 400), finalized: true,
    };
  }

  graph(address: string, network: NetworkId, depth: number, tokenSymbol: string | null): Graph {
    this.assertReachable(network);
    const rng = new Rng(`graph:${network}:${address}`);
    const tokens = tokensOf(network).filter((t) => !tokenSymbol || t.symbol === tokenSymbol);
    if (tokens.length === 0) return { root: address, nodes: [], edges: [], truncated: false };
    const nodes = new Map<string, GraphNode>();
    const edges: GraphEdge[] = [];
    const mk = (a: string, d: number): GraphNode => {
      const lbl = this.labels.find((l) => l.address === a && l.network === network);
      return { id: a, address: a, network, label: lbl ? lbl.category : null, riskLevel: lbl?.evidenceLevel === 'CONFIRMED_BY_SOURCE' ? 'CRITICAL' : lbl?.category === 'mixer' ? 'HIGH' : rng.pick(['LOW', 'LOW', 'MEDIUM', 'UNKNOWN'] as RiskLevel[]), isContract: rng.next() < 0.1, depth: d };
    };
    nodes.set(address, { ...mk(address, 0), riskLevel: this.risk(address, network).level });
    let frontier = [address];
    for (let d = 1; d <= depth; d++) {
      const next: string[] = [];
      for (const src of frontier) {
        const fan = d === 1 ? rng.int(4, 6) : rng.int(1, 3);
        for (let k = 0; k < fan && nodes.size < 80; k++) {
          const a = fakeAddress(network, rng.int(1, 120));
          if (!nodes.has(a)) {
            nodes.set(a, mk(a, d));
            next.push(a);
          }
          const t = rng.pick(tokens);
          const out = rng.next() < 0.55;
          edges.push({ id: `e${edges.length}`, source: out ? src : a, target: out ? a : src, txHash: fakeTxHash(network, rng), token: t, rawAmount: rng.raw(t.decimals, 5), timestamp: iso(rng.int(1, 30 * 24) * H) });
        }
      }
      frontier = next;
    }
    return { root: address, nodes: [...nodes.values()], edges, truncated: nodes.size >= 80 };
  }

  /** Mock of GET /dashboard, computed from this fake store. */
  dashboard(): Dashboard {
    const open = this.alerts.filter((a) => a.status === 'OPEN');
    const count = <K extends string>(keys: readonly K[], f: (k: K) => number) => Object.fromEntries(keys.map((k) => [k, f(k)])) as Record<K, number>;
    const levels: RiskLevel[] = ['UNKNOWN', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
    const start = Math.floor(NOW / H) * H - 23 * H;
    return {
      generatedAt: new Date(NOW).toISOString(),
      totals: { walletsTracked: 160, eventsIndexed: 48_200, transfersIndexed: 47_900, eventsRetracted: 3, rules: this.rules.length,
        rulesEnabled: this.rules.filter((r) => r.enabled).length, alerts: this.alerts.length, openAlerts: open.length,
        signals24h: this.signals.filter((x) => Date.parse(x.detectedAt) > NOW - D).length },
      riskDistribution: count(levels, (l) => ({ UNKNOWN: 12, LOW: 96, MEDIUM: 31, HIGH: 15, CRITICAL: 6 })[l]),
      networks: this.networks(),
      activity: Array.from({ length: 24 }, (_, i) => ({ start: new Date(start + i * H).toISOString(), events: 1800 + ((i * 37) % 400),
        alerts: this.alerts.filter((a) => Math.floor(Date.parse(a.createdAt) / H) * H === start + i * H).length,
        signals: this.signals.filter((x) => Math.floor(Date.parse(x.detectedAt) / H) * H === start + i * H).length })),
      alerts: {
        byStatus: count(ALERT_STATUSES, (st) => this.alerts.filter((a) => a.status === st).length),
        bySeverity: count(SEVERITIES, (sv) => this.alerts.filter((a) => a.severity === sv).length),
        openBySeverity: count(SEVERITIES, (sv) => open.filter((a) => a.severity === sv).length),
        recent: this.alerts.slice(0, 5),
      },
      recentSignals: this.signals.slice(0, 10),
      omittedSections: [],
    };
  }

  simulate(yaml: string, from: string, to: string): RuleTestResult {
    const parsed = yamlToRule(yaml);
    if (!parsed.draft || parsed.errors.length) throw new ProblemError(422, 'Rule invalid', parsed.errors.map((e) => `${e.path || 'line ' + e.line}: ${e.message}`).join('; '));
    const d = parsed.draft;
    const days = Math.max(1 / 24, (Date.parse(to) - Date.parse(from)) / D);
    const rng = new Rng(`sim:${yaml}`);
    const conditions = JSON.stringify(d.when).split('"condition"').length - 1;
    const nets: NetworkId[] = d.networks.length ? d.networks : ['ethereum'];
    const evaluated = Math.round(days * 48_000 * nets.length);
    const selectivity = Math.pow(0.08, conditions) * (d.window ? 0.2 : 1) * (0.5 + rng.next());
    const matchCount = Math.min(evaluated, Math.round(evaluated * selectivity));
    const perDay = Math.round((matchCount / days) * 10) / 10;
    const cooldownCap = 86400 / Math.max(1, durationSeconds(d.cooldown));
    const estimated = Math.min(perDay, cooldownCap * 20);
    const warnings: string[] = [];
    const permissive = estimated > 50 || conditions <= 1;
    if (estimated > 50) warnings.push(`~${estimated} alerts/day expected: analysts will not keep up. Add conditions or raise thresholds.`);
    if (conditions <= 1) warnings.push('Only one condition: rules this broad usually produce noise.');
    const matches = Array.from({ length: Math.min(matchCount, 25) }, () => {
      const n = rng.pick(nets);
      const t = rng.pick(tokensOf(n));
      const amount = rng.raw(0, 6);
      return { eventId: `${n}:${fakeTxHash(n, rng)}:${rng.int(0, 5)}`, network: n, eventTime: new Date(Date.parse(to) - rng.int(0, Math.round(days * D))).toISOString(), summary: `${amount} ${t.symbol} ${fakeAddress(n, rng.int(1, 60)).slice(0, 12)}… → ${fakeAddress(n, rng.int(61, 99)).slice(0, 12)}…`, fields: { 'token.symbol': t.symbol, amount } };
    });
    return { evaluatedEvents: evaluated, matchCount, estimatedTriggersPerDay: estimated, permissive, warnings, matches };
  }
}

export function durationSeconds(d: string): number {
  const m = /^(\d+)(s|m|h|d)$/.exec(d);
  if (!m) return 0;
  return Number(m[1]) * { s: 1, m: 60, h: 3600, d: 86400 }[m[2] as 's' | 'm' | 'h' | 'd'];
}

export const SEVERITY_ORDER = SEVERITIES;
