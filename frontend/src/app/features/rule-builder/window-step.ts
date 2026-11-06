import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { FieldDef, RuleValidationError } from '../../core/models';
import { AGGREGATES, RuleDraft, WindowSpec } from '../../core/rules/rule-model';

/** Step 3: optional time-window aggregation ("sum of amount per sender over 1h > 1,000,000"). */
@Component({
  selector: 'oci-window-step',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toggle">
      <button type="button" class="switch" role="switch" [attr.aria-checked]="!!draft().window" (click)="toggle()" aria-labelledby="win-l"></button>
      <div><span id="win-l" class="strong">Aggregate over a time window</span>
        <p class="hint">Fires when the aggregate of matching events over the window crosses the threshold. Leave it off to alert on every single matching event.</p></div>
    </div>
    @if (draft().window; as w) {
      <div class="grid win">
        <label>Aggregate<select name="agg" [ngModel]="w.aggregate" (ngModelChange)="set({ aggregate: $event })">@for (a of aggregates; track a) { <option [value]="a">{{ aggLabel(a) }}</option> }</select></label>
        @if (w.aggregate !== 'count') {
          <label>Of field<select name="wf" [ngModel]="w.field" (ngModelChange)="set({ field: $event })">@for (f of catalog(); track f.path) { <option [value]="f.path">{{ f.path }}</option> }</select></label>
        }
        <label>Per<select name="gb" [ngModel]="w.groupBy[0] ?? ''" (ngModelChange)="set({ groupBy: $event ? [$event, ...w.groupBy.slice(1)] : [] })"><option value="">Whole rule</option>@for (f of catalog(); track f.path) { <option [value]="f.path">{{ f.path }}</option> }</select></label>
        <label>Over<input name="wd" [ngModel]="w.duration" (ngModelChange)="set({ duration: $event })" placeholder="1h" [attr.aria-invalid]="err('window.duration') ? true : null" /></label>
        <label>Is<select name="wo" [ngModel]="w.op" (ngModelChange)="set({ op: $event })"><option value="gt">greater than</option><option value="gte">at least</option></select></label>
        <label>Threshold<input name="wv" inputmode="decimal" [ngModel]="w.value" (ngModelChange)="set({ value: $event })" [attr.aria-invalid]="err('window.value') ? true : null" /></label>
      </div>
      <p class="well sentence">{{ summary(w) }}</p>
      @for (m of errsUnder(); track $index) { <span class="field-error">{{ m }}</span> }
    }
  `,
  styles: `
    :host { display: grid; gap: 16px; }
    .toggle { display: flex; gap: 12px; align-items: flex-start; }
    .toggle .switch { margin-top: 2px; }
    .toggle p { margin: 2px 0 0; }
    .win { grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 16px; }
    .sentence { margin: 0; font-size: 15px; }
  `,
})
export class WindowStep {
  readonly draft = input.required<RuleDraft>();
  readonly catalog = input<FieldDef[]>([]);
  readonly errors = input<RuleValidationError[]>([]);
  readonly patch = output<Partial<RuleDraft>>();
  readonly aggregates = AGGREGATES;

  aggLabel(a: string): string {
    return ({ count: 'Count of events', sum: 'Sum', distinct_count: 'Distinct count', max: 'Maximum', ratio_of_balance: 'Ratio of balance' } as Record<string, string>)[a] ?? a;
  }
  summary(w: WindowSpec): string {
    const what = w.aggregate === 'count' ? 'Number of matching events' : `${this.aggLabel(w.aggregate)} of ${w.field}`;
    const per = w.groupBy.length ? ` per ${w.groupBy.join(', ')}` : '';
    return `${what}${per} over ${w.duration || '…'} is ${w.op === 'gte' ? 'at least' : 'greater than'} ${w.value || '…'}.`;
  }
  err(path: string): string | null {
    return this.errors().find((e) => e.path === path)?.message ?? null;
  }
  errsUnder(): string[] {
    return this.errors().filter((e) => e.path === 'window' || e.path.startsWith('window.')).map((e) => e.message);
  }
  set(p: Partial<WindowSpec>): void {
    const w = this.draft().window;
    if (w) this.patch.emit({ window: { ...w, ...p } });
  }
  toggle(): void {
    this.patch.emit({ window: this.draft().window ? null : { duration: '1h', aggregate: 'sum', field: 'transaction.amount', groupBy: ['transaction.from'], op: 'gt', value: '1000000' } });
  }
}
