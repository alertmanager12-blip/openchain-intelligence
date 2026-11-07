import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { EvidenceLevel, NETWORKS, NetworkId, RiskContribution } from '../../core/models';
import { detectQuery } from '../../core/network-detect';
import { apiResource } from '../../shared/api-resource';
import { formatAge, timeAgo, netLabel } from '../../shared/format';
import { MAT_BUTTONS, MAT_FORM, MAT_TABLE } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { TableState } from '../../shared/table-state';
import { UI, sentence } from '../../shared/ui';

const EVIDENCE_ORDER: Record<EvidenceLevel, number> = { ANOMALY: 0, RISK_SIGNAL: 1, CONFIRMED_BY_SOURCE: 2 };

@Component({
  selector: 'oci-risk-page',
  imports: [RouterLink, PageHeader, ...MAT_BUTTONS, ...MAT_FORM, ...MAT_TABLE, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './risk.html',
  styleUrl: './risk.scss',
})
export class RiskPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  readonly network = input<NetworkId>();
  readonly address = input<string>();

  readonly networks = NETWORKS;
  readonly netLabel = netLabel;
  /** Form fields follow the route and stay editable. */
  readonly addrInput = linkedSignal(() => this.address() ?? '');
  readonly netInput = linkedSignal<NetworkId>(() => this.network() ?? 'ethereum');
  readonly risk = apiResource({
    params: () => (this.address() && this.network() ? { a: this.address()!, n: this.network()! } : undefined),
    stream: (p) => this.api.walletRisk(p.a, p.n),
  });
  readonly ago = timeAgo;
  readonly age = formatAge;
  readonly sentence = sentence;
  readonly open = signal<string | null>(null);
  readonly cols = ['x', 'factor', 'ev', 'points', 'confidence', 'age'];
  readonly byFactor = (_: number, c: RiskContribution) => c.factor;

  readonly t = new TableState<RiskContribution>(
    computed(() => this.risk.value()?.contributions ?? []),
    { ev: (c) => EVIDENCE_ORDER[c.evidenceLevel], points: (c) => c.points, confidence: (c) => c.confidence, age: (c) => c.ageSeconds },
    (c) => c.factor,
    { key: 'points', dir: 'desc' },
  );
  readonly top = computed(() => [...(this.risk.value()?.contributions ?? [])].sort((a, b) => b.points - a.points)[0] ?? null);
  readonly strongest = computed(() => {
    const cs = this.risk.value()?.contributions ?? [];
    return cs.reduce<EvidenceLevel | null>((m, c) => (m === null || EVIDENCE_ORDER[c.evidenceLevel] > EVIDENCE_ORDER[m] ? c.evidenceLevel : m), null);
  });

  toggle(f: string): void {
    this.open.update((v) => (v === f ? null : f));
  }

  submit(): void {
    const d = detectQuery(this.addrInput());
    const n = d.candidates.includes(this.netInput()) ? this.netInput() : (d.candidates[0] ?? this.netInput());
    this.router.navigate(['/risk', n, d.normalized || this.addrInput().trim()]);
  }
}
