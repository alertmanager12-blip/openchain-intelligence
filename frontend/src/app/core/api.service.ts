import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  Alert, AlertStatus, ApiKey, ChainTransaction, Exposure, FieldDef, Graph, Label, LabelReport, NetworkId, NetworkStatus,
  Page, RiskScore, Rule, RuleTestRequest, RuleTestResult, RuleValidationResult, Severity, Signal, TransactionAnalysis,
  User, WalletAnalysis, Webhook,
} from './models';

type Params = Record<string, string | number | boolean | null | undefined>;

function params(p: Params): HttpParams {
  let hp = new HttpParams();
  for (const [k, v] of Object.entries(p)) if (v !== null && v !== undefined && v !== '') hp = hp.set(k, String(v));
  return hp;
}

export interface AlertQuery {
  status?: AlertStatus | '';
  severity?: Severity | '';
  cursor?: string | null;
  limit?: number;
}

export interface GraphQuery {
  network: NetworkId;
  depth: number;
  token?: string;
  from?: string;
  to?: string;
}

/**
 * Typed client for /api/v1. Every error surfaces as an ApiError (see errorInterceptor).
 * In mock mode the same calls are answered by mockBackendInterceptor.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;
  private enc = encodeURIComponent;

  // --- networks & signals
  networks(): Observable<NetworkStatus[]> {
    return this.http.get<NetworkStatus[]>(`${this.base}/networks`);
  }
  recentSignals(limit = 20): Observable<Page<Signal>> {
    return this.http.get<Page<Signal>>(`${this.base}/signals`, { params: params({ limit }) });
  }

  // --- wallets & transactions
  walletAnalysis(address: string, network: NetworkId): Observable<WalletAnalysis> {
    return this.http.get<WalletAnalysis>(`${this.base}/wallets/${this.enc(address)}/analysis`, { params: params({ network }) });
  }
  walletTransactions(address: string, network: NetworkId, cursor?: string | null, limit = 25): Observable<Page<ChainTransaction>> {
    return this.http.get<Page<ChainTransaction>>(`${this.base}/wallets/${this.enc(address)}/transactions`, { params: params({ network, cursor, limit }) });
  }
  walletRisk(address: string, network: NetworkId): Observable<RiskScore> {
    return this.http.get<RiskScore>(`${this.base}/wallets/${this.enc(address)}/risk`, { params: params({ network }) });
  }
  walletExposure(address: string, network: NetworkId): Observable<Exposure> {
    return this.http.get<Exposure>(`${this.base}/wallets/${this.enc(address)}/exposure`, { params: params({ network }) });
  }
  transactionAnalysis(hash: string, network: NetworkId): Observable<TransactionAnalysis> {
    return this.http.get<TransactionAnalysis>(`${this.base}/transactions/${this.enc(hash)}/analysis`, { params: params({ network }) });
  }
  graph(address: string, q: GraphQuery): Observable<Graph> {
    return this.http.get<Graph>(`${this.base}/graph/${this.enc(address)}`, { params: params({ ...q }) });
  }

  // --- rules
  rules(): Observable<Page<Rule>> {
    return this.http.get<Page<Rule>>(`${this.base}/rules`);
  }
  rule(id: string): Observable<Rule> {
    return this.http.get<Rule>(`${this.base}/rules/${this.enc(id)}`);
  }
  createRule(yaml: string): Observable<Rule> {
    return this.http.post<Rule>(`${this.base}/rules`, { yaml });
  }
  updateRule(id: string, yaml: string): Observable<Rule> {
    return this.http.put<Rule>(`${this.base}/rules/${this.enc(id)}`, { yaml });
  }
  deleteRule(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/rules/${this.enc(id)}`);
  }
  enableRule(id: string, enabled: boolean): Observable<Rule> {
    return this.http.post<Rule>(`${this.base}/rules/${this.enc(id)}/${enabled ? 'enable' : 'disable'}`, {});
  }
  validateRule(yaml: string): Observable<RuleValidationResult> {
    return this.http.post<RuleValidationResult>(`${this.base}/rules/validate`, { yaml });
  }
  testRule(req: RuleTestRequest): Observable<RuleTestResult> {
    return req.ruleId
      ? this.http.post<RuleTestResult>(`${this.base}/rules/${this.enc(req.ruleId)}/test`, req)
      : this.http.post<RuleTestResult>(`${this.base}/rules/test`, req);
  }
  importRules(yaml: string): Observable<Rule[]> {
    return this.http.post<Rule[]>(`${this.base}/rules/import`, yaml, { headers: { 'Content-Type': 'application/yaml' } });
  }
  exportRules(): Observable<string> {
    return this.http.get(`${this.base}/rules/export`, { responseType: 'text' });
  }
  fieldCatalog(): Observable<FieldDef[]> {
    return this.http.get<FieldDef[]>(`${this.base}/rules/fields`);
  }

  // --- alerts
  alerts(q: AlertQuery = {}): Observable<Page<Alert>> {
    return this.http.get<Page<Alert>>(`${this.base}/alerts`, { params: params({ ...q }) });
  }
  alert(id: string): Observable<Alert> {
    return this.http.get<Alert>(`${this.base}/alerts/${this.enc(id)}`);
  }
  acknowledgeAlert(id: string): Observable<Alert> {
    return this.http.post<Alert>(`${this.base}/alerts/${this.enc(id)}/acknowledge`, {});
  }
  setAlertStatus(id: string, status: AlertStatus, reason?: string): Observable<Alert> {
    return this.http.post<Alert>(`${this.base}/alerts/${this.enc(id)}/status`, { status, reason });
  }
  assignAlert(id: string, assignee: string | null): Observable<Alert> {
    return this.http.post<Alert>(`${this.base}/alerts/${this.enc(id)}/assign`, { assignee });
  }
  commentAlert(id: string, body: string): Observable<Alert> {
    return this.http.post<Alert>(`${this.base}/alerts/${this.enc(id)}/comments`, { body });
  }

  // --- integrations
  webhooks(): Observable<Webhook[]> {
    return this.http.get<Webhook[]>(`${this.base}/webhooks`);
  }
  createWebhook(w: Pick<Webhook, 'type' | 'name' | 'target'>): Observable<Webhook> {
    return this.http.post<Webhook>(`${this.base}/webhooks`, w);
  }
  deleteWebhook(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/webhooks/${this.enc(id)}`);
  }
  testWebhook(id: string): Observable<{ delivered: boolean; statusCode: number | null; message: string }> {
    return this.http.post<{ delivered: boolean; statusCode: number | null; message: string }>(`${this.base}/webhooks/${this.enc(id)}/test`, {});
  }

  // --- community intelligence
  labels(q: { address?: string; network?: NetworkId | ''; cursor?: string | null } = {}): Observable<Page<Label>> {
    return this.http.get<Page<Label>>(`${this.base}/labels`, { params: params({ ...q }) });
  }
  reportLabel(r: LabelReport): Observable<LabelReport> {
    return this.http.post<LabelReport>(`${this.base}/labels/reports`, r);
  }
  labelReports(): Observable<Page<LabelReport>> {
    return this.http.get<Page<LabelReport>>(`${this.base}/labels/reports`);
  }

  // --- administration
  users(): Observable<User[]> {
    return this.http.get<User[]>(`${this.base}/users`);
  }
  updateUserRole(id: string, role: User['role']): Observable<User> {
    return this.http.patch<User>(`${this.base}/users/${this.enc(id)}`, { role });
  }
  apiKeys(): Observable<ApiKey[]> {
    return this.http.get<ApiKey[]>(`${this.base}/api-keys`);
  }
  createApiKey(name: string, scopes: string[]): Observable<ApiKey> {
    return this.http.post<ApiKey>(`${this.base}/api-keys`, { name, scopes });
  }
  revokeApiKey(id: string): Observable<ApiKey> {
    return this.http.post<ApiKey>(`${this.base}/api-keys/${this.enc(id)}/revoke`, {});
  }
}
