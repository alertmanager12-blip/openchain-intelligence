import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatTooltip } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { ApiError } from '../core/api-error';
import { EvidenceLevel, NetworkId, RiskLevel, Severity } from '../core/models';
import { RiskGauge } from './risk-gauge';
import { netLabel, sentence, shortHash } from './format';
import { Skeleton, SkeletonKind } from './skeleton';

/** Re-exported for the pages: sentence() lives in format.ts so the app shell can use it without loading these components. */
export { sentence };

/**
 * Loading / error / empty wrapper. Content is projected only when there is something to show.
 * While loading for the first time it shows a skeleton shaped like the content (table, readout or lines).
 */
@Component({
  selector: 'oci-state',
  imports: [Skeleton, MatButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (error(); as e) {
      <div class="state state--error" role="alert">
        <strong>{{ e.title }}</strong>
        @if (e.detail) { <p>{{ e.detail }}</p> }
        @for (f of e.fieldErrors; track $index) { <p class="mono small">{{ f.field }}: {{ f.message }}</p> }
        @if (e.retryable || retryAlways()) { <button matButton="outlined" class="sm" type="button" (click)="retry.emit()">Retry</button> }
      </div>
    } @else if (loading() && !hasData()) {
      <oci-skeleton [kind]="skeleton()" [rows]="skeletonRows()" [columns]="skeletonColumns()" [label]="loadingText()" [style.--cols]="gridCols()" />
    } @else if (empty()) {
      <div class="empty" role="status"><span>{{ emptyText() }}</span><ng-content select="[empty]" /></div>
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
  readonly loadingText = input('Loading');
  readonly emptyText = input('Nothing to show yet.');
  readonly skeleton = input<SkeletonKind>('lines');
  readonly skeletonRows = input(5);
  readonly skeletonColumns = input<number[]>([70, 90, 60, 80, 50]);
  readonly gridCols = computed(() => this.skeletonColumns().map(() => 'minmax(0, 1fr)').join(' '));
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
  imports: [MatTooltip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Hover shows the explanation; matTooltip also registers it as the badge's aria-describedby text for screen readers.
  template: `<span class="badge ev-{{ value().toLowerCase() }}" [matTooltip]="help()">{{ text() }}</span>`,
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
  template: `<span class="chip net net-{{ value() }}">{{ label() }}</span>`,
})
export class NetworkChip {
  readonly value = input.required<NetworkId>();
  readonly label = computed(() => netLabel(this.value()));
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
  imports: [MatButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<button matButton class="sm" type="button" (click)="copy()" [attr.aria-label]="done() ? 'Copied' : label()">
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

/**
 * Collapsed "Technical detail" disclosure: the plain-language reading comes first, the formulas, factor keys,
 * detector ids and raw evidence stay one click (or Enter / Space) away. Native details/summary, so it works
 * with the keyboard and screen readers without script; aria-expanded mirrors the open state for older AT.
 */
@Component({
  selector: 'oci-tech',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<details class="tech" [open]="open()" (toggle)="open.set($any($event.target).open)">
      <summary [attr.aria-expanded]="open()">{{ label() }}</summary>
      <div class="tech-body"><ng-content /></div>
    </details>`,
  styles: `
    :host { display: block; min-width: 0; }
    .tech summary { cursor: pointer; width: fit-content; font-size: 12px; color: var(--muted); padding: 2px 0; border-radius: 4px; list-style: none; display: inline-flex; align-items: center; gap: 4px; }
    .tech summary::-webkit-details-marker { display: none; }
    .tech summary::before { content: ''; width: 0; height: 0; border-left: 4px solid currentColor; border-top: 3.5px solid transparent; border-bottom: 3.5px solid transparent; transition: transform 120ms ease; }
    .tech[open] summary::before { transform: rotate(90deg); }
    .tech summary:hover { color: var(--ink-2); }
    .tech summary:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
    .tech-body { margin-top: 6px; padding: 8px 10px; border-left: 2px solid var(--line); font-size: 12px; color: var(--ink-2); display: grid; gap: 4px; overflow-wrap: anywhere; }
  `,
})
export class TechDetail {
  readonly label = input('Technical detail');
  readonly open = signal(false);
}

export const UI = [StateComponent, SeverityBadge, EvidenceBadge, RiskBadge, NetworkChip, AddressLink, RiskGauge, CopyButton, TechDetail] as const;
