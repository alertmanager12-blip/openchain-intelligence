import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatSlideToggle } from '@angular/material/slide-toggle';
import { I18nService, hasKey } from '../../core/i18n/i18n.service';
import { FieldDef, RuleValidationError } from '../../core/models';
import { AGGREGATES, RuleDraft, WindowSpec } from '../../core/rules/rule-model';
import { ruleErrorText } from '../../core/rules/rule-yaml';
import { MAT_FORM } from '../../shared/material';

/** Step 3: optional time-window aggregation ("sum of amount per sender over 1h > 1,000,000"). */
@Component({
  selector: 'oci-window-step',
  imports: [FormsModule, MatSlideToggle, ...MAT_FORM],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toggle">
      <mat-slide-toggle hideIcon [checked]="!!draft().window" (change)="toggle()" aria-describedby="win-h">{{ i18n.t('rules.window.toggle') }}</mat-slide-toggle>
      <p class="hint" id="win-h">{{ i18n.t('rules.window.hint') }}</p>
    </div>
    @if (draft().window; as w) {
      <div class="grid win">
        <mat-form-field>
          <mat-label>{{ i18n.t('rules.window.aggregate') }}</mat-label>
          <mat-select [value]="w.aggregate" (valueChange)="set({ aggregate: $event })">@for (a of aggregates; track a) { <mat-option [value]="a">{{ aggLabel(a) }}</mat-option> }</mat-select>
        </mat-form-field>
        @if (w.aggregate !== 'count') {
          <mat-form-field>
            <mat-label>{{ i18n.t('rules.window.ofField') }}</mat-label>
            <mat-select [value]="w.field" (valueChange)="set({ field: $event })">@for (f of catalog(); track f.path) { <mat-option [value]="f.path"><span class="mono">{{ f.path }}</span></mat-option> }</mat-select>
          </mat-form-field>
        }
        <mat-form-field>
          <mat-label>{{ i18n.t('rules.window.per') }}</mat-label>
          <mat-select [value]="w.groupBy[0] ?? ''" (valueChange)="set({ groupBy: $event ? [$event, ...w.groupBy.slice(1)] : [] })">
            <mat-option value="">{{ i18n.t('rules.window.wholeRule') }}</mat-option>@for (f of catalog(); track f.path) { <mat-option [value]="f.path"><span class="mono">{{ f.path }}</span></mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field>
          <mat-label>{{ i18n.t('rules.window.over') }}</mat-label>
          <input matInput name="wd" [ngModel]="w.duration" (ngModelChange)="set({ duration: $event })" placeholder="1h" [ociInvalid]="!!err('window.duration')" />
        </mat-form-field>
        <mat-form-field>
          <mat-label>{{ i18n.t('rules.window.is') }}</mat-label>
          <mat-select [value]="w.op" (valueChange)="set({ op: $event })"><mat-option value="gt">{{ i18n.t('rules.winOp.gt') }}</mat-option><mat-option value="gte">{{ i18n.t('rules.winOp.gte') }}</mat-option></mat-select>
        </mat-form-field>
        <mat-form-field>
          <mat-label>{{ i18n.t('rules.window.threshold') }}</mat-label>
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
  protected readonly i18n = inject(I18nService);
  readonly aggregates = AGGREGATES;

  aggLabel(a: string): string {
    const k = `rules.agg.${a}`;
    return hasKey(k) ? this.i18n.t(k) : a;
  }
  summary(w: WindowSpec): string {
    const t = this.i18n.t.bind(this.i18n);
    const what = w.aggregate === 'count' ? t('rules.win.whatCount') : t('rules.win.whatOf', { agg: this.aggLabel(w.aggregate), field: w.field ?? '' });
    const per = w.groupBy.length ? t('rules.win.per', { fields: w.groupBy.join(', ') }) : '';
    return t('rules.win.summary', { what, per, duration: w.duration || '…', cmp: t(w.op === 'gte' ? 'rules.win.cmp.gte' : 'rules.win.cmp.gt'), value: w.value || '…' });
  }
  err(path: string): string | null {
    const e = this.errors().find((x) => x.path === path);
    return e ? ruleErrorText(e, this.i18n.lang()) : null;
  }
  errsUnder(): string[] {
    return this.errors().filter((e) => e.path === 'window' || e.path.startsWith('window.')).map((e) => ruleErrorText(e, this.i18n.lang()));
  }
  set(p: Partial<WindowSpec>): void {
    const w = this.draft().window;
    if (w) this.patch.emit({ window: { ...w, ...p } });
  }
  toggle(): void {
    this.patch.emit({ window: this.draft().window ? null : { duration: '1h', aggregate: 'sum', field: 'transaction.amount', groupBy: ['transaction.from'], op: 'gt', value: '1000000' } });
  }
}
