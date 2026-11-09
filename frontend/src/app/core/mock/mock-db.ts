/* In-memory fake data for mock mode. Every address and hash here is fictitious ("fa4ede30" ≈ fake demo). */
import {
  ALERT_STATUSES, Alert, AlertStatus, ApiKey, Balance, Dashboard, ChainTransaction, Counterparty, EvidenceLevel, Exposure, Graph, GraphEdge, GraphNode, HistoryCoverage, Label,
  LabelReport, NETWORKS, NetworkId, NetworkStatus, RiskContribution, RiskLevel, RiskScore, Rule, RuleTestResult, SEVERITIES, Severity,
  Signal, Token, TransactionAnalysis, TokenTransfer, User, WalletAnalysis, Webhook,
} from '../models';
import { RuleDraft, emptyRule, newCondition, newGroup } from '../rules/rule-model';
import { labelTitle, signalTypeTitle } from '../../shared/explain';
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

const n0 = (v: number) => v.toLocaleString('en-US');

/**
 * Plain-language sentence of a signal type, in the wording of oci-core SignalText / the detectors
 * ("Sent 95% of its USDT balance (35,000 USDT) to 2 addresses within 2 minutes"). Numbers come from `rng`.
 */
function plainSummary(type: string, rng: Rng, sym: string): string {
  const amt = n0(rng.int(5, 400) * 500);
  switch (type) {
    case 'UNUSUAL_AMOUNT': return `Sent ${amt} ${sym} in one transfer, ${rng.int(8, 30)} times its median transfer of the last 90 days`;
    case 'FAST_FORWARDING': return `Forwarded ${rng.int(85, 99)}% of the ${amt} ${sym} it received within ${rng.int(2, 9)} minutes`;
    case 'STRUCTURING': return `Sent ${rng.int(8, 14)} transfers just below 10,000 ${sym} within 2 hours`;
    case 'DORMANT_REACTIVATION': return `Became active again after ${rng.int(200, 900)} days without activity and moved ${amt} ${sym}`;
    case 'MIXER_EXPOSURE': return `Received ${amt} ${sym} directly from an address listed as a mixer by a community source`;
    case 'SANCTIONED_EXPOSURE': return `Sent ${amt} ${sym} directly to an address listed as sanctioned by demo-official-list`;
    case 'INDIRECT_EXPOSURE': return `Received ${amt} ${sym} that came ${rng.int(2, 3)} hops from an address listed as sanctioned`;
    case 'CIRCULAR_FLOW': return `${amt} ${sym} came back to this wallet through ${rng.int(2, 5)} intermediate wallets within ${rng.int(2, 12)} hours`;
    case 'UNUSUAL_APPROVAL': return `Granted an unlimited ${sym} approval to a contract deployed ${rng.int(1, 6)} hours earlier`;
    case 'RAPID_DRAIN': return `Sent ${rng.int(90, 99)}% of its ${sym} balance (${amt} ${sym}) to ${rng.int(2, 6)} addresses within ${rng.int(2, 5)} minutes`;
    case 'FLOW_CONCENTRATION': return `Split ${amt} ${sym} across ${rng.int(12, 40)} new addresses within ${rng.int(10, 50)} minutes`;
    case 'DUSTING': return `Only linked to a listed address by ${rng.int(2, 9)} unsolicited dust transfers (under 0.01 ${sym}), which says nothing about the owner`;
    case 'OWNERSHIP_CHANGE': return `Contract ownership moved to a new address ${rng.int(1, 30)} hours ago`;
    default: return `${signalTypeTitle(type)} observed`;
  }
}

/** Short detector id as the backend names them ("RAPID_DRAIN" -> "rapid-drain@1"). */
const detectorOf = (type: string) => `${type.toLowerCase().replace(/_/g, '-')}@1`;

const humanAge = (sec: number) => (sec < 3600 ? `${Math.round(sec / 60)}m` : sec < 86400 ? `${Math.round(sec / 3600)}h` : `${Math.round(sec / 86400)}d`);

/** Plain-language description of the scoring method (RiskScore.methodSummary). */
const METHOD_SUMMARY = 'Each factor counts according to its confidence, its severity and its age (evidence loses half its weight every 30 days). '
  + 'Factors combine so that several independent reasons raise the score more than any single one, without ever exceeding 100.';
const METHOD = 'noisy-or saturating aggregation: score=100*(1-prod_f(1-p_f)), p_f=w_f*(1-prod_i(1-q_i)), q_i=confidence*severity*0.5^(age/halfLife); dedup by evidence per factor; halfLife=30d';

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
    // Separate RNG for the wording so the seeded data (signals, then alerts) stays identical.
    const text = { title: signalTypeTitle(t.type), summary: plainSummary(t.type, new Rng(`sigtext:${i}`), tokensOf(n)[1]?.symbol ?? 'USDT') };
    return {
      ...text,
      id: `sig_${i + 1}`, type: t.type, address: fakeAddress(n, rng.int(1, 40)), network: n, txHash: fakeTxHash(n, rng),
      severity: sev, confidence: Math.round((0.45 + rng.next() * 0.5) * 100) / 100, evidenceLevel: t.ev,
      evidence: [{ kind: 'observation', description: t.text, reference: null, source: t.ev === 'CONFIRMED_BY_SOURCE' ? 'demo-official-list' : 'oci-detector', observedAt: iso(rng.int(1, 72) * H) }],
      detectorId: detectorOf(t.type), detectedAt: iso(rng.int(1, 72 * 60) * 60_000),
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
        counterparty: null, blockNumber: null, evidenceSummary: null,
        summary: `${rule.name}: ${tok.symbol} transfer from ${address.slice(0, 12)}…`, evidenceLevel: rule.id === 'mixer-exposure' ? 'RISK_SIGNAL' : 'ANOMALY',
        assignee: status === 'INVESTIGATING' ? 'analyst@example.invalid' : null, createdAt: iso(created), updatedAt: iso(created - 20 * 60_000),
        matchedEvent: { type: 'TOKEN_TRANSFER', network: n, from: address, to: fakeAddress(n, rng.int(41, 80)), 'token.symbol': tok.symbol, amount: rng.raw(0, 6) },
        comments: [], history: history.reverse(),
      });
      this.attachEvidence(this.alerts[this.alerts.length - 1], tok.symbol, i);
    }
    this.alerts.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }

  /**
   * Engine evidence in the AlertDto.evidence shape (see oci-alerting RuleEngine): condition outcomes for
   * stateless rules, aggregate vs threshold over a window for windowed rules. Separate RNG so the
   * rest of the seeded data stays identical.
   */
  private attachEvidence(a: Alert, symbol: string, i: number): void {
    const rng = new Rng(`evidence:${a.id}`);
    const n = a.network;
    const ids = (k: number) => Array.from({ length: k }, (_, j) => `${n}:${fakeTxHash(n, rng)}:${j}`);
    const amount = String(rng.int(250_000, 2_400_000)) + '.' + String(rng.int(0, 99)).padStart(2, '0');
    a.ruleVersion = String(this.rules.find((r) => r.id === a.ruleId)?.version ?? 1);
    a.occurrenceCount = 1 + (i % 4 === 0 ? rng.int(1, 5) : 0);
    a.firstSeen = a.createdAt;
    a.lastSeen = a.updatedAt;
    a.counterparty = typeof a.matchedEvent['to'] === 'string' ? (a.matchedEvent['to'] as string) : null;
    a.blockNumber = 21_000_000 + rng.int(0, 900_000);
    const who = a.address ? `${a.address.slice(0, 10)}…` : 'The wallet';
    switch (a.ruleId) {
      case 'structuring-burst': {
        const count = rng.int(8, 14);
        a.matchedEventIds = ids(count);
        a.matchedEvent = { ...a.matchedEvent, 'token.symbol': 'USDT', amount: '9' + rng.int(100, 990) + '.' + rng.int(10, 99) };
        a.evidence = {
          kind: 'AGGREGATION', function: 'COUNT', value: String(count), operator: 'GTE', threshold: '8', window: 'PT2H',
          windowStartExclusive: new Date(Date.parse(a.createdAt) - 2 * H).toISOString(), windowEnd: a.createdAt, eventCount: count,
          groupBy: ['transaction.from'], evidenceLevel: a.evidenceLevel,
          conditions: [`transaction.token.symbol eq USDT (actual USDT) -> true`, `transaction.amount between [9000, 9999.99] (actual ${a.matchedEvent['amount']}) -> true`],
        };
        a.evidenceSummary = `${who} sent ${count} USDT transfers just below 10,000 USDT within 2 hours (threshold 8)`;
        break;
      }
      case 'mixer-exposure': {
        const usd = String(rng.int(10_000, 180_000));
        const rs = rng.int(66, 97);
        a.matchedEventIds = ids(1);
        a.evidence = {
          kind: 'STATELESS', evidenceLevel: a.evidenceLevel,
          conditions: [`counterparty.labels contains mixer (actual [mixer, community]) -> true`, `transaction.amount_usd gte 10000 (actual ${usd}) -> true`, `counterparty.risk_score gte 65 (actual ${rs}) -> true`],
        };
        a.evidenceSummary = `${who} received ${n0(Number(usd))} USD of ${symbol} from an address listed as a mixer by a community source (counterparty score ${rs})`;
        break;
      }
      case 'risky-approval': {
        a.matchedEventIds = ids(1);
        const days = rng.int(0, 6);
        a.evidence = { kind: 'STATELESS', evidenceLevel: a.evidenceLevel, conditions: [`approval.unlimited eq true (actual true) -> true`, `approval.spender_age_days lt 7 (actual ${days}) -> true`] };
        a.evidenceSummary = `${who} granted an unlimited ${symbol} approval to a contract deployed ${days === 0 ? 'less than a day' : days === 1 ? '1 day' : days + ' days'} earlier`;
        break;
      }
      default: {
        a.matchedEventIds = ids(1);
        a.matchedEvent = { ...a.matchedEvent, 'token.symbol': symbol === 'USDC' ? 'USDC' : 'USDT', amount };
        a.evidence = {
          kind: 'STATELESS', evidenceLevel: a.evidenceLevel,
          conditions: [`transaction.token.symbol in [USDT, USDC] (actual ${a.matchedEvent['token.symbol']}) -> true`, `transaction.amount gte 250000 (actual ${amount}) -> true`],
        };
        a.evidenceSummary = `${who} sent ${n0(Math.floor(Number(amount)))} ${a.matchedEvent['token.symbol']} in a single transfer (threshold 250,000)`;
      }
    }
  }

  // ---------------------------------------------------------------- read models

  /** Networks this mock instance does not index: GET /networks omits them and wallet calls answer 422, like the backend. */
  static readonly DISABLED: readonly NetworkId[] = ['bsc'];

  /** GET /networks: enabled networks only, or every network with `all` (disabled ones flagged, like the backend). */
  networks(all = false): NetworkStatus[] {
    return this.allNetworks().filter((n) => all || n.enabled);
  }

  private allNetworks(): NetworkStatus[] {
    const base: Record<NetworkId, number> = { ethereum: 21_934_120, tron: 71_204_118, bsc: 46_118_430, polygon: 69_502_331, bitcoin: 921_404, solana: 334_120_998 };
    return NETWORKS.map((n, i) => ({
      network: n, headBlock: base[n], finalizedBlock: base[n] - [64, 19, 15, 128, 6, 32][i], healthy: true,
      providerId: `${n}-primary`, latencyMillis: [180, 95, 140, 210, 420, 2900][i], checkedAt: iso(15_000),
      detail: null,
      enabled: !MockDb.DISABLED.includes(n), lastIndexedBlock: base[n] - [64, 19, 15, 128, 6, 32][i] - [2, 0, 1, 4, 0, 940][i], indexerLag: [64, 19, 15, 128, 6, 32][i] + [2, 0, 1, 4, 0, 940][i],
      simulated: n === 'polygon',
    }));
  }

  /** Mock-only sentinel: wallet calls for this address answer 503 (retryable), to exercise the provider-outage path. */
  static readonly OUTAGE_ADDRESS = '0x0000000000000000000000000000000000000503';

  private assertReachable(network: NetworkId, address?: string): void {
    // Same answer as the backend (InvestigationService.network): 422 problem+json with a /network field error, for a
    // disabled network or one without any provider adapter (bitcoin, solana).
    if (MockDb.DISABLED.includes(network) || !NETWORKS.includes(network))
      throw Object.assign(new ProblemError(422, 'Request validation failed', `network '${network}' is not enabled on this instance`),
        { errors: [{ pointer: '/network', path: '/network', code: 'unsupported_network', message: 'network not enabled' }] });
    if (address?.toLowerCase() === MockDb.OUTAGE_ADDRESS)
      throw new ProblemError(503, 'Provider unavailable', 'The provider is currently failing. Results are temporarily unavailable; retry in a minute.');
  }

  risk(address: string, network: NetworkId): RiskScore {
    this.assertReachable(network, address);
    const rng = new Rng(`risk:${network}:${address}`);
    const sym = tokensOf(network)[1]?.symbol ?? tokensOf(network)[0].symbol;
    const label = this.labels.find((l) => l.address === address && l.network === network);
    // Factor keys as the backend scoring engine emits them: "signal:<SignalType>" or "label:<CATEGORY>".
    const cands: [type: string, ev: EvidenceLevel, sev: Severity][] = [
      ['MIXER_EXPOSURE', 'RISK_SIGNAL', 'HIGH'], ['FAST_FORWARDING', 'ANOMALY', 'MEDIUM'], ['STRUCTURING', 'RISK_SIGNAL', 'MEDIUM'],
      ['RAPID_DRAIN', 'RISK_SIGNAL', 'MEDIUM'], ['FLOW_CONCENTRATION', 'ANOMALY', 'MEDIUM'], ['INDIRECT_EXPOSURE', 'RISK_SIGNAL', 'MEDIUM'],
      ['DUSTING', 'ANOMALY', 'LOW'], ['DORMANT_REACTIVATION', 'ANOMALY', 'LOW'], ['UNUSUAL_AMOUNT', 'ANOMALY', 'LOW'],
      ['CIRCULAR_FLOW', 'RISK_SIGNAL', 'MEDIUM'], ['UNUSUAL_APPROVAL', 'ANOMALY', 'LOW'], ['OWNERSHIP_CHANGE', 'ANOMALY', 'MEDIUM'],
    ];
    const SEV_MULT: Record<Severity, number> = { INFO: 0.2, LOW: 0.4, MEDIUM: 0.7, HIGH: 0.85, CRITICAL: 1 };
    const pool = [...cands];
    const picked: typeof cands = [];
    for (let k = rng.int(2, 5); k > 0 && pool.length; k--) picked.push(pool.splice(rng.int(0, pool.length - 1), 1)[0]);
    const factors: Omit<RiskContribution, 'points'>[] = picked.map(([type, ev, sev]) => {
      const weight = Math.round((0.1 + rng.next() * 0.4) * 100) / 100;
      const confidence = Math.round((0.4 + rng.next() * 0.55) * 100) / 100;
      const ageSeconds = rng.int(600, 30 * 86400);
      const decay = Math.pow(0.5, ageSeconds / (30 * 86400));
      const q = confidence * SEV_MULT[sev] * decay;
      const det = detectorOf(type);
      return {
        factor: `signal:${type}`, signalId: `sig_${rng.int(1, 36)}`, source: det, weight, confidence, ageSeconds, evidenceLevel: ev,
        title: signalTypeTitle(type), summary: plainSummary(type, rng, sym),
        explanation: `${type} ${sev} ${ev} by ${det}; confidence ${confidence.toFixed(2)}, severity x${SEV_MULT[sev].toFixed(2)}, age ${humanAge(ageSeconds)}, decay ${decay.toFixed(3)} -> q=${q.toFixed(3)}; factor weight ${weight.toFixed(2)}, factor p=${(weight * q).toFixed(3)}`,
      };
    });
    const labelFactor = (category: string, source: string, reliability: number, ev: EvidenceLevel, weight: number, summary: string): Omit<RiskContribution, 'points'> => ({
      factor: `label:${category}`, signalId: null, source, weight, confidence: reliability, ageSeconds: 86400 * 3, evidenceLevel: ev, title: labelTitle(category), summary,
      explanation: `listed as ${category.toLowerCase()} by source '${source}'; reliability ${reliability.toFixed(2)}, age 3d, decay ${Math.pow(0.5, 3 / 30).toFixed(3)}; factor weight ${weight.toFixed(2)}`,
    });
    if (rng.next() < 0.35) factors.push(labelFactor('EXCHANGE', 'oci-curated', 0.85, 'ANOMALY', 0.3, `Most outflows go to deposit addresses listed as an exchange by oci-curated, which lowers the score`));
    if ((network === 'ethereum' || network === 'tron') && rng.next() < 0.12)
      factors.unshift(labelFactor('ISSUER_FROZEN', `${sym.toLowerCase()}-issuer-blacklist`, 0.99, 'CONFIRMED_BY_SOURCE', 0.9, `${sym} held by this address was frozen by the stablecoin issuer (authoritative source)`));
    if (label?.evidenceLevel === 'CONFIRMED_BY_SOURCE')
      factors.unshift(labelFactor('SANCTIONED', label.source, label.reliability, 'CONFIRMED_BY_SOURCE', 1, `Listed as sanctioned by ${label.source} (authoritative source)`));
    const contributions = factors.map((f) => ({ ...f, points: Math.round(f.weight * f.confidence * 100 * (f.factor === 'label:EXCHANGE' ? -0.3 : 1)) }));
    const score = Math.max(0, Math.min(100, contributions.reduce((s, c) => s + c.points, 0)));
    const history = Array.from({ length: 12 }, (_, i) => {
      const s = Math.max(0, Math.min(100, score + rng.int(-15, 8) * (i === 11 ? 0 : 1)));
      return { at: iso((11 - i) * 5 * D), score: i === 11 ? score : s, level: RISK_FOR(i === 11 ? score : s) };
    });
    return { address, network, score, level: RISK_FOR(score), contributions, method: METHOD, methodSummary: METHOD_SUMMARY, modelVersion: 'demo-1.0', computedAt: iso(5 * 60_000), history };
  }

  /** Fetch count per wallet, so an Ethereum wallet's history goes RUNNING -> COMPLETE across polls (dev mock mode). */
  private readonly historyPolls = new Map<string, number>();

  /**
   * On-demand history coverage (ADR-0020). Simulated chains report NOT_CONFIGURED; Ethereum wallets load in two polls,
   * then end COMPLETE (or TRUNCATED for some); BSC is COMPLETE, or FAILED for some addresses.
   */
  history(address: string, network: NetworkId): HistoryCoverage {
    const base: HistoryCoverage = {
      status: 'NOT_CONFIGURED', sources: [], coveredFromBlock: null, coveredToBlock: null, coveredFromTime: null, tokenTransfersCovered: false,
      approvalsCovered: false, nativeCovered: false, internalCovered: false, truncated: false, eventsFound: 0,
      message: 'Simulated demo chain: the indexed demo data is the whole history.', updatedAt: null,
    };
    const head = this.allNetworks().find((s) => s.network === network)!.headBlock;
    const rng = new Rng(`hist:${network}:${address}`);
    const from = head - rng.int(400_000, 3_400_000);
    const events = rng.int(40, 4000);
    const logs = { sources: ['eth_getLogs'], tokenTransfersCovered: true, approvalsCovered: true, coveredToBlock: head, updatedAt: iso(10_000) };
    if (network === 'ethereum') {
      const key = `${network}:${address}`;
      const n = (this.historyPolls.get(key) ?? 0) + 1;
      this.historyPolls.set(key, n);
      if (n <= 2)
        return { ...base, ...logs, status: n === 1 ? 'PENDING' : 'RUNNING', coveredFromBlock: n === 1 ? null : head - 200_000, coveredToBlock: n === 1 ? null : head, eventsFound: n === 1 ? 0 : Math.round(events / 3),
          message: n === 1 ? 'History: queued, loading will start shortly (eth_getLogs).' : `History: loading ERC-20 transfers and approvals back from block ${n0(head)} (eth_getLogs).` };
      if (rng.next() < 0.25)
        return { ...base, ...logs, status: 'TRUNCATED', truncated: true, coveredFromBlock: head - 500_000, coveredFromTime: iso(70 * D), eventsFound: 10_000,
          message: `History: ERC-20 transfers and approvals since block ${n0(head - 500_000)} only: the 10,000-event cap was reached, older history is not loaded. Native and internal transfers are not covered: configure an Erigon node (ADR-0020).` };
      return { ...base, ...logs, status: 'COMPLETE', coveredFromBlock: from, coveredFromTime: iso(rng.int(100, 900) * D), eventsFound: events,
        message: `History: ERC-20 transfers and approvals since block ${n0(from)} (eth_getLogs). Native and internal transfers are not covered: configure an Erigon node (ADR-0020).` };
    }
    if (network === 'bsc' && rng.next() < 0.3)
      return { ...base, ...logs, status: 'FAILED', coveredFromBlock: null, coveredToBlock: null, tokenTransfersCovered: false, approvalsCovered: false, updatedAt: iso(60_000),
        message: 'History could not be loaded: the RPC provider timed out on eth_getLogs. Figures below use indexed data only; retry later.' };
    if (network === 'bsc')
      return { ...base, ...logs, status: 'COMPLETE', coveredFromBlock: from, coveredFromTime: iso(rng.int(100, 900) * D), eventsFound: events,
        message: `History: BEP-20 transfers and approvals since block ${n0(from)} (eth_getLogs). Native and internal transfers are not covered.` };
    return base;
  }

  wallet(address: string, network: NetworkId): WalletAnalysis {
    this.assertReachable(network, address);
    const rng = new Rng(`wallet:${network}:${address}`);
    const tokens = tokensOf(network);
    const balances: Balance[] = tokens.map((t) => ({ token: t, rawAmount: rng.raw(t.decimals, 5), atBlock: this.allNetworks().find((s) => s.network === network)!.headBlock, observedAt: iso(30_000) }));
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
      history: this.history(address, network),
    };
  }

  transactions(address: string, network: NetworkId, cursor: string | null, limit: number): { items: ChainTransaction[]; nextCursor: string | null } {
    this.assertReachable(network, address);
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
    this.assertReachable(network, address);
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
    this.assertReachable(network, address);
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

