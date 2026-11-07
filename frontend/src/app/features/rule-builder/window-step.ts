import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatSlideToggle } from '@angular/material/slide-toggle';
import { FieldDef, RuleValidationError } from '../../core/models';
import { AGGREGATES, RuleDraft, WindowSpec } from '../../core/rules/rule-model';
import { MAT_FORM } from '../../shared/material';

/** Step 3: optional time-window aggregation ("sum of amount per sender over 1h > 1,000,000"). */
@Component({
  selector: 'oci-window-step',
  imports: [FormsModule, MatSlideToggle, ...MAT_FORM],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toggle">
      <mat-slide-toggle hideIcon [checked]="!!draft().window" (change)="toggle()" aria-describedby="win-h">Aggregate over a time window</mat-slide-toggle>
      <p class="hint" id="win-h">Fires when the aggregate of matching events over the window crosses the threshold. Leave it off to alert on every single matching event.</p>
    </div>
    @if (draft().window; as w) {
      <div class="grid win">
        <mat-form-field>
          <mat-label>Aggregate</mat-label>
          <mat-select [value]="w.aggregate" (valueChange)="set({ aggregate: $event })">@for (a of aggregates; track a) { <mat-option [value]="a">{{ aggLabel(a) }}</mat-option> }</mat-select>
        </mat-form-field>
        @if (w.aggregate !== 'count') {
          <mat-form-field>
            <mat-label>Of field</mat-label>
            <mat-select [value]="w.field" (valueChange)="set({ field: $event })">@for (f of catalog(); track f.path) { <mat-option [value]="f.path"><span class="mono">{{ f.path }}</span></mat-option> }</mat-select>
          </mat-form-field>
        }
        <mat-form-field>
          <mat-label>Per</mat-label>
          <mat-select [value]="w.groupBy[0] ?? ''" (valueChange)="set({ groupBy: $event ? [$event, ...w.groupBy.slice(1)] : [] })">
            <mat-option value="">Whole rule</mat-option>@for (f of catalog(); track f.path) { <mat-option [value]="f.path"><span class="mono">{{ f.path }}</span></mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Over</mat-label>
          <input matInput name="wd" [ngModel]="w.duration" (ngModelChange)="set({ duration: $event })" placeholder="1h" [ociInvalid]="!!err('window.duration')" />
        </mat-form-field>
        <mat-form-field>
          <mat-label>Is</mat-label>
          <mat-select [value]="w.op" (valueChange)="set({ op: $event })"><mat-option value="gt">greater than</mat-option><mat-option value="gte">at least</mat-option></mat-select>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Threshold</mat-label>
          <input matInput name="wv" inputmode="decimal" [ngModel]="w.value" (ngModelChange)="set({ value: $event })" [ociInvalid]="!!err('window.value')" />
        </mat-form-field>
      </div>
      <p class="well sentence">{{ summary(w) }}</p>
      @for (m of errsUnder(); track $index) { <span class="field-error">{{ m }}</span> }
    }
  `,
  styles: `
    :host { display: grid; gap: 16px; }
    .toggle { display: grid; gap: 2px; }
    .toggle p { margin: 0 0 0 60px; }
    .win { grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 4px 16px; }
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
