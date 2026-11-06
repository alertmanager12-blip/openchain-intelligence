import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiError } from '../core/api-error';
import { EvidenceLevel, NetworkId, RiskLevel, Severity } from '../core/models';
import { RiskGauge } from './risk-gauge';
import { shortHash } from './format';

/** "FALSE_POSITIVE" -> "False positive": enum values are shown in sentence case. */
export function sentence(v: string): string {
  const t = v.replace(/_/g, ' ').toLowerCase();
  return t.charAt(0).toUpperCase() + t.slice(1);
}

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
      <div class="state state--empty" role="status"><span>{{ emptyText() }}</span><ng-content select="[empty]" /></div>
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
  template: `<span class="badge sev-{{ value().toLowerCase() }}">{{ text() }}</span>`,
})
export class SeverityBadge {
  readonly value = input.required<Severity>();
  readonly text = computed(() => sentence(this.value()));
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
  readonly text = computed(() => sentence(this.value()));
  readonly help = computed(() => EVIDENCE_HELP[this.value()]);
}

@Component({
  selector: 'oci-risk',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="badge risk-{{ value().toLowerCase() }}">@if (score() !== null) { <span class="num">{{ score() }}</span> }{{ value() === 'UNKNOWN' ? 'Not scored' : sentence(value()) }}</span>`,
})
export class RiskBadge {
  readonly value = input.required<RiskLevel>();
  readonly score = input<number | null>(null);
  readonly sentence = sentence;
}

@Component({
  selector: 'oci-net',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="chip net net-{{ value() }}">{{ value() }}</span>`,
})
export class NetworkChip {
  readonly value = input.required<NetworkId>();
}

/** Address rendered as a link to its wallet page, shortened with the full value in title. */
@Component({
  selector: 'oci-addr',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<a class="mono" [class.nowrap]="!full()" [routerLink]="['/wallets', network(), address()]" [attr.title]="address()">{{ full() ? address() : short() }}</a>`,
})
export class AddressLink {
  readonly address = input.required<string>();
  readonly network = input.required<NetworkId>();
  readonly full = input(false);
  readonly short = computed(() => shortHash(this.address(), 8, 6));
}

/** Copies a value to the clipboard and confirms it for a moment (announced politely). */
@Component({
  selector: 'oci-copy',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<button class="btn btn--sm btn--ghost" type="button" (click)="copy()" [attr.aria-label]="done() ? 'Copied' : label()">
      <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.4"><rect x="5.5" y="5.5" width="8" height="8" rx="1"/><path d="M10.5 3.5v-1h-8v8h1"/></svg>
      <span aria-live="polite">{{ done() ? 'Copied' : 'Copy' }}</span></button>`,
})
export class CopyButton {
  readonly value = input.required<string>();
  readonly label = input('Copy to clipboard');
  readonly done = signal(false);
  copy(): void {
    void navigator.clipboard?.writeText(this.value()).then(() => {
      this.done.set(true);
      setTimeout(() => this.done.set(false), 1600);
    }).catch(() => undefined);
  }
}

export const UI = [StateComponent, SeverityBadge, EvidenceBadge, RiskBadge, NetworkChip, AddressLink, RiskGauge, CopyButton] as const;
