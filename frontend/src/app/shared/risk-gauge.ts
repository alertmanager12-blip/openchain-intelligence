import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RiskLevel } from '../core/models';

/**
 * Engine thresholds (io.openchain.core.risk.RiskLevel.fromScore):
 * LOW < 35 <= MEDIUM < 65 <= HIGH < 85 <= CRITICAL.
 */
export const RISK_THRESHOLDS = { MEDIUM: 35, HIGH: 65, CRITICAL: 85 } as const;

export const RISK_BANDS: readonly { level: Exclude<RiskLevel, 'UNKNOWN'>; from: number; to: number }[] = [
  { level: 'LOW', from: 0, to: RISK_THRESHOLDS.MEDIUM },
  { level: 'MEDIUM', from: RISK_THRESHOLDS.MEDIUM, to: RISK_THRESHOLDS.HIGH },
  { level: 'HIGH', from: RISK_THRESHOLDS.HIGH, to: RISK_THRESHOLDS.CRITICAL },
  { level: 'CRITICAL', from: RISK_THRESHOLDS.CRITICAL, to: 100 },
];

/** Clamps to 0..100 and rounds; null / NaN stay null (not scored). */
export function clampScore(score: number | null | undefined): number | null {
  if (score === null || score === undefined || Number.isNaN(score)) return null;
  return Math.round(Math.min(100, Math.max(0, score)));
}

/** Same banding as the backend. A missing score is UNKNOWN, never LOW. */
export function riskBand(score: number | null | undefined): RiskLevel {
  const s = clampScore(score);
  if (s === null) return 'UNKNOWN';
  if (s >= RISK_THRESHOLDS.CRITICAL) return 'CRITICAL';
  if (s >= RISK_THRESHOLDS.HIGH) return 'HIGH';
  if (s >= RISK_THRESHOLDS.MEDIUM) return 'MEDIUM';
  return 'LOW';
}

export function levelLabel(l: RiskLevel): string {
  return l === 'UNKNOWN' ? 'Not scored' : l.charAt(0) + l.slice(1).toLowerCase();
}

/**
 * The risk gauge: a segmented 0-100 scale with ticks at the engine thresholds, filled up to the score,
 * with a marker and the score in tabular figures. `size="lg"` for wallet / risk / alert detail,
 * `size="sm"` inline in tables and lists.
 */
@Component({
  selector: 'oci-gauge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    role: 'meter',
    'aria-valuemin': '0',
    'aria-valuemax': '100',
    '[attr.aria-valuenow]': 'value()',
    '[attr.aria-valuetext]': 'valueText()',
    '[attr.aria-label]': 'label()',
    '[attr.title]': "size() === 'sm' ? valueText() : null",
    '[class]': "'gauge gauge--' + size() + ' lv-' + level().toLowerCase()",
  },
  template: `
    @if (size() === 'lg') {
      <div class="readout" aria-hidden="true">
        <span class="score">{{ value() ?? '–' }}</span><span class="of">/ 100</span>
        <span class="lvl">{{ levelText() }}</span>
      </div>
    } @else {
      <span class="score" aria-hidden="true">{{ value() ?? '–' }}</span>
    }
    <span class="scale" aria-hidden="true">
      @for (b of segments(); track b.level) {
        <span class="seg b-{{ b.level.toLowerCase() }}" [style.flex-grow]="b.to - b.from"><span class="fill" [style.width.%]="b.fill"></span></span>
      }
      @if (value() !== null) { <span class="marker" [style.left.%]="value()"></span> }
    </span>
    @if (size() === 'lg') {
      <span class="ticks" aria-hidden="true">
        @for (t of ticks; track t) { <span class="tick" [style.left.%]="t">{{ t }}</span> }
      </span>
    }
  `,
  styles: `
    :host { display: inline-grid; grid-template-columns: 3ch minmax(64px, 1fr); align-items: center; gap: 8px; min-width: 0; font-variant-numeric: tabular-nums; vertical-align: middle; }
    :host(.gauge--lg) { display: grid; grid-template-columns: 1fr; gap: 6px; width: 100%; max-width: 520px; }
    .score { font-weight: 600; text-align: right; color: var(--ink); }
    .readout { display: flex; align-items: baseline; gap: 6px; }
    .readout .score { font-size: 40px; line-height: 1; letter-spacing: -0.02em; text-align: left; }
    .of { color: var(--muted); font-size: 13px; }
    .lvl { margin-left: 8px; font-size: 16px; font-weight: 600; }
    :host(.lv-low) .lvl { color: var(--risk-low-text); }
    :host(.lv-medium) .lvl { color: var(--risk-medium-text); }
    :host(.lv-high) .lvl { color: var(--risk-high-text); }
    :host(.lv-critical) .lvl { color: var(--risk-critical-text); }
    :host(.lv-unknown) .lvl { color: var(--muted); font-weight: 500; }
    .scale { position: relative; display: flex; height: 6px; }
    :host(.gauge--lg) .scale { height: 12px; }
    .seg { position: relative; flex-basis: 0; overflow: hidden; background: color-mix(in srgb, var(--c) 22%, var(--sunken)); }
    .fill { position: absolute; inset: 0 auto 0 0; background: var(--c); }
    .b-low { --c: var(--risk-low); } .b-medium { --c: var(--risk-medium); } .b-high { --c: var(--risk-high); } .b-critical { --c: var(--risk-critical); }
    :host(.lv-unknown) .seg { background: var(--sunken); }
    .marker { position: absolute; top: -3px; bottom: -3px; width: 2px; margin-left: -1px; background: var(--ink); }
    :host(.gauge--lg) .marker { top: -5px; bottom: -5px; }
    .ticks { position: relative; height: 16px; font-size: 12px; color: var(--muted); }
    .tick { position: absolute; top: 0; transform: translateX(-50%); }
    .tick:first-child { transform: none; }
    .tick:last-child { transform: translateX(-100%); }
  `,
})
export class RiskGauge {
  readonly score = input<number | null | undefined>(null);
  /** Level from the backend; derived from the score when absent. */
  readonly levelIn = input<RiskLevel | null | undefined>(null, { alias: 'level' });
  readonly size = input<'lg' | 'sm'>('sm');
  readonly label = input('Risk score');

  readonly ticks = [0, RISK_THRESHOLDS.MEDIUM, RISK_THRESHOLDS.HIGH, RISK_THRESHOLDS.CRITICAL, 100];
  readonly value = computed(() => clampScore(this.score()));
  readonly level = computed<RiskLevel>(() => (this.value() === null ? 'UNKNOWN' : (this.levelIn() ?? riskBand(this.value()))));
  readonly levelText = computed(() => levelLabel(this.level()));
  readonly valueText = computed(() => (this.value() === null ? 'Not scored' : `${this.value()} of 100, ${this.levelText().toLowerCase()} risk`));
  readonly segments = computed(() => {
    const v = this.value() ?? 0;
    return RISK_BANDS.map((b) => ({ ...b, fill: Math.max(0, Math.min(100, ((v - b.from) / (b.to - b.from)) * 100)) }));
  });
}
