import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiError } from '../core/api-error';
import { EvidenceLevel, NetworkId, RiskLevel, Severity } from '../core/models';
import { shortHash } from './format';

/** Loading / error / empty wrapper. Content is projected only when there is something to show. */
@Component({
  selector: 'oci-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (error(); as e) {
      <div class="state state--error" role="alert">
        <strong>{{ e.title }}</strong>
        @if (e.detail) { <p>{{ e.detail }}</p> }
        @for (f of e.fieldErrors; track $index) { <p class="mono small">{{ f.field }}: {{ f.message }}</p> }
        @if (e.retryable || retryAlways()) { <button class="btn btn--sm" type="button" (click)="retry.emit()">Retry</button> }
      </div>
    } @else if (loading() && !hasData()) {
      <div class="state" aria-busy="true" aria-live="polite">
        <span class="spinner" aria-hidden="true"></span><span>{{ loadingText() }}</span>
      </div>
    } @else if (empty()) {
      <div class="state state--empty"><span>{{ emptyText() }}</span><ng-content select="[empty]" /></div>
    } @else {
      <ng-content />
    }
  `,
})
export class StateComponent {
  readonly loading = input(false);
  readonly error = input<ApiError | null>(null);
  readonly empty = input(false);
  readonly hasData = input(false);
  readonly retryAlways = input(true);
  readonly loadingText = input('Loading…');
  readonly emptyText = input('Nothing to show yet.');
  readonly retry = output<void>();
}

@Component({
  selector: 'oci-severity',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="badge sev-{{ value().toLowerCase() }}">{{ value() }}</span>`,
})
export class SeverityBadge {
  readonly value = input.required<Severity>();
}

const EVIDENCE_HELP: Record<EvidenceLevel, string> = {
  ANOMALY: 'Statistical anomaly: unusual behaviour, no risk claim.',
  RISK_SIGNAL: 'Risk signal: pattern associated with risk. Not proof of wrongdoing.',
  CONFIRMED_BY_SOURCE: 'Confirmed by a cited external source (e.g. official sanctions list).',
};

@Component({
  selector: 'oci-evidence',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="badge ev-{{ value().toLowerCase() }}" [attr.title]="help()">{{ text() }}</span>`,
})
export class EvidenceBadge {
  readonly value = input.required<EvidenceLevel>();
  readonly text = computed(() => this.value().replace(/_/g, ' '));
  readonly help = computed(() => EVIDENCE_HELP[this.value()]);
}

@Component({
  selector: 'oci-risk',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="badge risk-{{ value().toLowerCase() }}">{{ score() !== null ? score() + ' · ' : '' }}{{ value() }}</span>`,
})
export class RiskBadge {
  readonly value = input.required<RiskLevel>();
  readonly score = input<number | null>(null);
}

@Component({
  selector: 'oci-net',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="chip net-{{ value() }}">{{ value() }}</span>`,
})
export class NetworkChip {
  readonly value = input.required<NetworkId>();
}

/** Address rendered as a link to its wallet page, shortened with the full value in title. */
@Component({
  selector: 'oci-addr',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<a class="mono" [routerLink]="['/wallets', network(), address()]" [attr.title]="address()">{{ full() ? address() : short() }}</a>`,
})
export class AddressLink {
  readonly address = input.required<string>();
  readonly network = input.required<NetworkId>();
  readonly full = input(false);
  readonly short = computed(() => shortHash(this.address(), 8, 6));
}

export const UI = [StateComponent, SeverityBadge, EvidenceBadge, RiskBadge, NetworkChip, AddressLink] as const;
