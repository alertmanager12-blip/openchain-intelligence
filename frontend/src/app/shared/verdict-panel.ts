import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { I18nService } from '../core/i18n/i18n.service';
import { RiskLevel } from '../core/models';
import { EvidenceLine } from './evidence-line';
import { Level } from './levels';
import { RiskBadge } from './ui';

export interface VerdictLine { level: Level; text: string; detail?: string | null; cite?: string | null; /** The engine's own wording, shown on demand. */ original?: string | null; /** Set to "en" when the text is the engine's English wording inside a French page. */ textLang?: string | null }

/**
 * A verdict (spec 2026-11-18): one plain sentence, the risk, the findings that support it with their level of proof, and
 * the coverage sentence, which is always shown ("Coverage unknown" when none is given, never left out).
 */
@Component({
  selector: 'oci-verdict',
  imports: [EvidenceLine, RiskBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="verdict">
      <div class="head">
        <p class="sentence">{{ sentence() }}</p>
        @if (risk(); as r) { <oci-risk [value]="r" /> }
      </div>
      @if (lines().length) {
        <div class="lines">@for (l of lines(); track $index) { <oci-evidence-line [level]="l.level" [text]="l.text" [detail]="l.detail ?? null" [cite]="l.cite ?? null" [original]="l.original ?? null" [textLang]="l.textLang ?? null" /> }</div>
      }
      <p class="coverage">{{ coverage() || i18n.t('verdict.coverageUnknown') }}@if (limit()) { {{ ' ' + limit() }} }</p>
    </section>`,
  styles: `
    .verdict { background: var(--band); border-radius: var(--r-panel); padding: 16px 18px; display: grid; gap: 10px; }
    .head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
    .sentence { font-size: 17px; line-height: 24px; font-weight: 600; margin: 0; max-width: 60ch; min-width: 0; overflow-wrap: anywhere; color: var(--ink); }
    .lines { display: grid; gap: 6px; }
    .coverage { margin: 0; color: var(--ink-2); font-size: var(--fs-12); max-width: 80ch; }
  `,
})
export class VerdictPanel {
  protected readonly i18n = inject(I18nService);
  readonly sentence = input.required<string>();
  readonly risk = input<RiskLevel | null>(null);
  readonly lines = input<VerdictLine[]>([]);
  readonly coverage = input.required<string>();
  readonly limit = input<string | null>(null);
}
