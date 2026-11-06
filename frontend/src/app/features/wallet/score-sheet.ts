import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RiskScore } from '../../core/models';
import { formatAge } from '../../shared/format';
import { UI, sentence } from '../../shared/ui';

/**
 * Measurement sheet of a risk score: positive contributions first (largest first), then reductions. Each row's
 * bar starts where the previous one ended, so the bars together end at the score (or at the 100 cap).
 */
@Component({
  selector: 'oci-score-sheet',
  imports: [...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="table-wrap"><table class="sheet">
      <thead><tr>
        <th scope="col">Factor</th><th scope="col">Evidence</th>
        <th scope="col" class="right">Confidence</th><th scope="col" class="right">Age</th><th scope="col" class="right">Points</th>
        <th scope="col" class="bar-col"><span class="sr-only">Running total of points</span><span aria-hidden="true" class="scale-head"><span>0</span><span>{{ sheet().domain }}</span></span></th>
      </tr></thead>
      <tbody>
        @for (c of sheet().rows; track c.factor) {
          <tr>
            <td><span class="strong">{{ sentence(c.factor) }}</span><div class="sub expl">{{ c.explanation }} <span class="src">Source {{ c.source }}</span></div></td>
            <td><oci-evidence [value]="c.evidenceLevel" /></td>
            <td class="right">{{ pct(c.confidence) }}%</td>
            <td class="right nowrap">{{ age(c.ageSeconds) }}</td>
            <td class="right pts" [class.neg]="c.points < 0">{{ c.points > 0 ? '+' : '' }}{{ c.points }}</td>
            <td class="bar-col"><span class="track" aria-hidden="true">@for (t of sheet().ticks; track $index) { <span class="thr" [style.left.%]="t"></span> }@if (sheet().cap; as cap) { <span class="cap" [style.left.%]="cap"></span> }
              <span class="piece" [class.neg]="c.points < 0" [style.left.%]="c.left" [style.width.%]="c.width"></span></span></td>
          </tr>
        } @empty { <tr><td colspan="6" class="muted">No contribution: nothing notable was observed on this wallet.</td></tr> }
      </tbody>
      <tfoot><tr><td colspan="4">Score</td><td class="right pts">{{ risk().score }}</td><td class="bar-col"><oci-gauge [score]="risk().score" [level]="risk().level" label="Total score" /></td></tr></tfoot>
    </table></div>
    @if (rawTotal() !== risk().score) { <p class="small muted">The contributions add up to {{ rawTotal() }}; the score is capped at {{ risk().score }} (red line).</p> }
  `,
  styles: `
    .sheet { min-width: 760px; }
    .expl { max-width: 56ch; display: block; }
    .src { color: var(--muted); display: block; }
    .pts { font: 600 16px var(--font-display); }
    .neg { color: var(--ok); }
    .bar-col { width: 28%; min-width: 180px; }
    .scale-head { display: flex; justify-content: space-between; }
    .track { position: relative; display: block; height: 8px; border-radius: 4px; background: var(--well); box-shadow: var(--shadow-inset); }
    .thr { position: absolute; top: 0; bottom: 0; width: 1px; background: var(--muted); opacity: 0.45; }
    .cap { position: absolute; top: -3px; bottom: -3px; width: 2px; margin-left: -1px; background: var(--risk-critical); z-index: 1; }
    .piece { position: absolute; top: 1px; bottom: 1px; background: var(--ink-2); border-radius: 2px; min-width: 2px; }
    .piece.neg { background: var(--risk-low); }
    tfoot oci-gauge { width: 100%; }
    @media (max-width: 640px) {
      .sheet thead { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
      .sheet, .sheet tbody, .sheet tfoot { display: block; min-width: 0; width: 100%; }
      .sheet tbody tr, .sheet tfoot tr { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; gap: 6px 12px; grid-template-areas: 'f f p' 'e c a' 'b b b'; padding: 12px 0; border-bottom: 1px solid var(--line); }
      .sheet td { display: block; padding: 0; border: 0; text-align: left; }
      .sheet tbody td:nth-child(1) { grid-area: f; } .sheet tbody td:nth-child(2) { grid-area: e; }
      .sheet tbody td:nth-child(3) { grid-area: c; } .sheet tbody td:nth-child(4) { grid-area: a; }
      .sheet tbody td:nth-child(5) { grid-area: p; text-align: right; } .sheet tbody td:nth-child(6) { grid-area: b; }
      .sheet tbody td:nth-child(3)::before { content: 'Confidence '; color: var(--muted); }
      .sheet tbody td:nth-child(4)::before { content: 'Age '; color: var(--muted); }
      .sheet tbody td[colspan] { grid-column: 1 / -1; }
      .sheet tfoot tr { grid-template-areas: 'l p' 'b b'; grid-template-columns: minmax(0, 1fr) auto; }
      .sheet tfoot td:nth-child(1) { grid-area: l; } .sheet tfoot td:nth-child(2) { grid-area: p; text-align: right; } .sheet tfoot td:nth-child(3) { grid-area: b; }
      .bar-col { width: auto; min-width: 0; }
    }
  `,
})
export class ScoreSheet {
  readonly risk = input.required<RiskScore>();
  readonly sentence = sentence;
  readonly age = formatAge;
  readonly pct = (v: number) => (v * 100).toFixed(0);
  /** Total before clamping, to explain when the displayed score differs from the plain sum. */
  readonly rawTotal = computed(() => this.risk().contributions.reduce((s, c) => s + c.points, 0));
  readonly sheet = computed(() => {
    const cs = [...this.risk().contributions].sort((a, b) => ((a.points >= 0) === (b.points >= 0) ? Math.abs(b.points) - Math.abs(a.points) : a.points >= 0 ? -1 : 1));
    let run = 0;
    const rows = cs.map((c) => {
      const from = Math.max(0, run);
      run += c.points;
      return { ...c, from, to: Math.max(0, run) };
    });
    // The track spans 0..100, or 0..running maximum when contributions add up past the 100 cap.
    const domain = Math.max(100, ...rows.map((r) => Math.max(r.from, r.to)));
    const at = (v: number) => (v / domain) * 100;
    return {
      domain,
      ticks: [35, 65, 85, 100].map(at),
      cap: domain > 100 ? at(100) : null,
      rows: rows.map((r) => ({ ...r, left: at(Math.min(r.from, r.to)), width: at(Math.abs(r.to - r.from)) })),
    };
  });
}
