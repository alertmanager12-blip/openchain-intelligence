import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { I18nService } from '../../core/i18n/i18n.service';
import { FieldDef, FieldType, RuleValidationError } from '../../core/models';
import {
  Condition, ConditionGroup, ConditionValue, OPERATORS_BY_TYPE, Operator, availabilityNote, fieldDescription, fieldTypeName, newCondition, newGroup, operatorLabel,
} from '../../core/rules/rule-model';
import { ruleErrorText } from '../../core/rules/rule-yaml';
import { MAT_BUTTONS, MAT_FORM } from '../../shared/material';

/** Label category ids as written in wallet.labels / counterparty.labels by the backend. */
const LABEL_CATEGORIES = ['sanctioned', 'mixer', 'scam', 'phishing', 'ransomware', 'stolen-funds', 'darknet-market', 'flagged-community', 'exchange', 'bridge', 'defi-protocol', 'other'];
const MAX_DEPTH = 4;

/** Default value for a field type / operator pair, so a freshly switched condition is immediately valid-ish. */
export function defaultValue(type: FieldType | undefined, op: Operator, def?: FieldDef): ConditionValue {
  if (op === 'exists') return null;
  if (op === 'in' || op === 'not_in') return [];
  if (op === 'between') return type === 'number' ? ([0, 100] as unknown as string[]) : ['0', '0'];
  switch (type) {
    case 'boolean': return true;
    case 'number': return 0;
    case 'enum': return def?.options?.[0] ?? '';
    default: return '';
  }
}

/**
 * Recursive WHEN editor. It edits the group in place and emits `changed` so the page can recompute
 * the YAML preview and validation.
 */
@Component({
  selector: 'oci-condition-group',
  imports: [FormsModule, MatButtonToggleModule, ...MAT_BUTTONS, ...MAT_FORM],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="group" [class.nested]="depth() !== 1" role="group" [attr.aria-label]="groupAria()">
      <div class="row head">
        <span class="kw">{{ i18n.t(depth() === 1 ? 'rules.cond.when' : 'rules.cond.group') }}</span>
        <mat-button-toggle-group [aria-label]="i18n.t('rules.cond.logicAria')" [value]="group().match" (change)="setMatch($event.value)">
          <mat-button-toggle value="all">{{ i18n.t('rules.cond.all') }}</mat-button-toggle>
          <mat-button-toggle value="any">{{ i18n.t('rules.cond.any') }}</mat-button-toggle>
          <mat-button-toggle value="not">{{ i18n.t('rules.cond.none') }}</mat-button-toggle>
        </mat-button-toggle-group>
        <span class="spacer"></span>
        @if (removable()) { <button matButton class="sm danger" type="button" (click)="remove.emit()" [attr.aria-label]="i18n.t('rules.cond.removeGroup')">{{ i18n.t('rules.cond.removeGroup') }}</button> }
      </div>

      @for (ch of group().children; track ch.id; let i = $index; let first = $first) {
        @if (!first) { <div class="joiner">{{ i18n.t(group().match === 'any' ? 'rules.cond.or' : group().match === 'not' ? 'rules.cond.nor' : 'rules.cond.and') }}</div> }
        @if (ch.kind === 'group') {
          <oci-condition-group [group]="ch" [catalog]="catalog()" [depth]="depth() + 1" [removable]="true" [errors]="errors()" [path]="childPath(i)" (changed)="changed.emit()" (remove)="removeAt(i)" />
        } @else {
          <div class="cond" [class.invalid]="hasError(childPath(i))">
            <mat-form-field class="f">
              <mat-label>{{ i18n.t('rules.cond.field') }}</mat-label>
              <mat-select [value]="ch.field" (valueChange)="setField(ch, $event)" panelWidth="auto" [ociInvalid]="hasError(childPath(i))">
                @for (f of catalog(); track f.path) { <mat-option [value]="f.path"><span class="mono">{{ f.path }}</span> <span class="muted small">{{ typeName(f.type) }}{{ note(f.available) }}</span></mat-option> }
              </mat-select>
            </mat-form-field>
            <mat-form-field class="o">
              <mat-label>{{ i18n.t('rules.cond.operator') }}</mat-label>
              <mat-select [value]="ch.op" (valueChange)="setOp(ch, $event)">
                @for (op of ops(ch); track op) { <mat-option [value]="op">{{ opName(op) }}</mat-option> }
              </mat-select>
            </mat-form-field>
            <div class="v">
              @switch (inputKind(ch)) {
                @case ('none') { <span class="small muted none">{{ i18n.t('rules.cond.noValue') }}</span> }
                @case ('bool') {
                  <mat-form-field><mat-label>{{ i18n.t('rules.cond.value') }}</mat-label><mat-select [value]="ch.value" (valueChange)="set(ch, $event)"><mat-option [value]="true">{{ i18n.t('rules.cond.true') }}</mat-option><mat-option [value]="false">{{ i18n.t('rules.cond.false') }}</mat-option></mat-select></mat-form-field>
                }
                @case ('enum') {
                  <mat-form-field><mat-label>{{ i18n.t('rules.cond.value') }}</mat-label><mat-select [value]="ch.value" (valueChange)="set(ch, $event)">@for (o of def(ch)?.options ?? []; track o) { <mat-option [value]="o">{{ o }}</mat-option> }</mat-select></mat-form-field>
                }
                @case ('label') {
                  <mat-form-field><mat-label>{{ i18n.t('rules.cond.category') }}</mat-label><mat-select [value]="ch.value" (valueChange)="set(ch, $event)">@for (o of labelCats; track o) { <mat-option [value]="o">{{ o }}</mat-option> }</mat-select></mat-form-field>
                }
                @case ('list') {
                  <mat-form-field><mat-label>{{ i18n.t('rules.cond.values') }}</mat-label><input matInput [ngModel]="listText(ch)" (ngModelChange)="setList(ch, $event)" [placeholder]="def(ch)?.options?.join(', ') ?? 'a, b, c'" /></mat-form-field>
                }
                @case ('range') {
                  <div class="range">
                    <mat-form-field><mat-label>{{ i18n.t('rules.cond.min') }}</mat-label><input matInput [attr.inputmode]="numeric(ch) ? 'decimal' : null" [ngModel]="rangeAt(ch, 0)" (ngModelChange)="setRange(ch, 0, $event)" /></mat-form-field>
                    <mat-form-field><mat-label>{{ i18n.t('rules.cond.max') }}</mat-label><input matInput [attr.inputmode]="numeric(ch) ? 'decimal' : null" [ngModel]="rangeAt(ch, 1)" (ngModelChange)="setRange(ch, 1, $event)" /></mat-form-field>
                  </div>
                }
                @case ('number') {
                  <mat-form-field><mat-label>{{ i18n.t('rules.cond.value') }}</mat-label><input matInput type="number" [ngModel]="ch.value" (ngModelChange)="set(ch, $event === null || $event === '' ? null : +$event)" /></mat-form-field>
                }
                @default {
                  <mat-form-field><mat-label>{{ i18n.t('rules.cond.value') }}</mat-label><input matInput [attr.inputmode]="def(ch)?.type === 'amount' ? 'decimal' : null" [ngModel]="ch.value" (ngModelChange)="set(ch, $event)"
                    [placeholder]="valuePlaceholder(ch)" /></mat-form-field>
                }
              }
            </div>
            <button matIconButton class="x" type="button" (click)="removeAt(i)" [disabled]="group().children.length === 1 && depth() === 1" [attr.aria-label]="i18n.t('rules.cond.removeAria', { n: i + 1 })" [matTooltip]="i18n.t('rules.cond.remove')">
              <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m4 4 8 8M12 4l-8 8"/></svg></button>
            @for (e of errorsFor(childPath(i)); track $index) { <p class="field-error full">{{ errText(e) }}</p> }
            @if (def(ch); as d) { <p class="small muted full">{{ describe(d) }}</p> }
          </div>
        }
      }
      @if (hasOwnError()) { <p class="field-error">{{ ownText() }}</p> }
      <div class="row add">
        <button matButton="outlined" class="sm" type="button" (click)="addCondition()">{{ i18n.t('rules.cond.add') }}</button>
        @if (canNest()) { <button matButton="outlined" class="sm" type="button" (click)="addGroup()">{{ i18n.t('rules.cond.addGroup') }}</button> }
      </div>
    </div>
  `,
  styles: `
    .group { border: 1px solid var(--line); border-radius: var(--r-well); padding: 12px 14px; display: grid; gap: 8px; background: var(--surface); }
    .nested { background: var(--surface-2); border-left: 3px solid var(--accent); }
    .head { gap: 12px; }
    .kw { font: 600 15px var(--font-display); color: var(--ink); min-width: 48px; }
    .joiner { font: 600 13px var(--font-display); color: var(--accent); padding-left: 12px; }
    .cond { display: grid; grid-template-columns: minmax(180px, 1.3fr) minmax(130px, 0.8fr) minmax(160px, 1.4fr) auto; gap: 0 10px; align-items: start; padding: 10px 0 8px; border-bottom: 1px solid var(--line-soft); }
    .cond.invalid { border-bottom-color: var(--risk-critical); }
    .cond mat-form-field { width: 100%; }
    .range { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
    .none { display: block; padding-top: 14px; }
    .full { grid-column: 1 / -1; margin: 2px 0 0; }
    .x { margin-top: 4px; }
    :host { display: block; min-width: 0; }
    .head { flex-wrap: wrap; }
    @media (max-width: 720px) { mat-button-toggle-group { flex-direction: column; width: 100%; } .cond { row-gap: 6px; grid-template-columns: minmax(0, 1fr) auto; } .cond .o, .cond .v { grid-column: 1; } .x { grid-row: 1; grid-column: 2; } }
  `,
})
export class ConditionGroupEditor {
  readonly group = input.required<ConditionGroup>();
  readonly catalog = input.required<FieldDef[]>();
  readonly depth = input(1);
  readonly removable = input(false);
  readonly path = input('when');
  /** Validation errors keyed by path, from the page. */
  readonly errors = input<RuleValidationError[]>([]);
  readonly changed = output<void>();
  readonly remove = output<void>();

  protected readonly i18n = inject(I18nService);
  readonly labelCats = LABEL_CATEGORIES;
  readonly maxDepth = MAX_DEPTH;
  readonly ownErrors = computed(() => this.errors().filter((e) => e.path === this.path()));
  readonly hasOwnError = computed(() => this.ownErrors().length > 0);
  /** Texts built at render time, so they follow a language switch. */
  protected canNest(): boolean { return this.depth() < this.maxDepth; }
  protected groupAria(): string { return this.i18n.t(this.group().match === 'all' ? 'rules.cond.groupAll' : this.group().match === 'any' ? 'rules.cond.groupAny' : 'rules.cond.groupNone'); }
  protected valuePlaceholder(c: Condition): string {
    const t = this.def(c)?.type;
    return t === 'amount' ? this.i18n.t('rules.cond.amountPlaceholder') : t === 'timestamp' ? '2026-01-31T00:00:00Z' : '';
  }
  protected note(available: boolean | undefined): string { return availabilityNote(available, this.i18n.lang()); }
  protected opName(op: Operator): string { return operatorLabel(op, this.i18n.lang()); }
  protected typeName(t: FieldType): string { return fieldTypeName(t, this.i18n.lang()); }
  protected describe(f: FieldDef): string { return fieldDescription(f, this.i18n.lang()); }
  protected errText(e: RuleValidationError): string { return ruleErrorText(e, this.i18n.lang()); }
  protected ownText(): string { return this.ownErrors().map((e) => this.errText(e)).join(this.i18n.lang() === 'fr' ? '\u202f; ' : '; '); }

  childPath(i: number): string {
    return `${this.path()}.${this.group().match}[${i}]`;
  }
  errorsFor(p: string): RuleValidationError[] {
    return this.errors().filter((e) => e.path === p || e.path.startsWith(p + '.'));
  }
  hasError(p: string): boolean {
    return this.errorsFor(p).length > 0;
  }
  def(c: Condition): FieldDef | undefined {
    return this.catalog().find((f) => f.path === c.field);
  }
  ops(c: Condition): Operator[] {
    const t = this.def(c)?.type;
    return t ? OPERATORS_BY_TYPE[t] : [];
  }
  numeric(c: Condition): boolean {
    const t = this.def(c)?.type;
    return t === 'amount' || t === 'number';
  }
  inputKind(c: Condition): 'none' | 'bool' | 'enum' | 'label' | 'list' | 'range' | 'number' | 'text' {
    const t = this.def(c)?.type;
    if (c.op === 'exists') return 'none';
    if (c.op === 'contains' && c.field.endsWith('.labels')) return 'label';
    if (c.op === 'in' || c.op === 'not_in') return 'list';
    if (c.op === 'between') return 'range';
    if (t === 'boolean') return 'bool';
    if (t === 'enum') return 'enum';
    if (t === 'number') return 'number';
    return 'text';
  }
  listText(c: Condition): string {
    return Array.isArray(c.value) ? c.value.join(', ') : '';
  }
  rangeAt(c: Condition, i: number): string {
    return Array.isArray(c.value) ? String(c.value[i] ?? '') : '';
  }

  setMatch(m: 'all' | 'any' | 'not'): void {
    this.group().match = m;
    this.changed.emit();
  }
  setField(c: Condition, field: string): void {
    c.field = field;
    const d = this.def(c);
    const ops = d ? OPERATORS_BY_TYPE[d.type] : [];
    if (!ops.includes(c.op)) c.op = ops[0];
    c.value = defaultValue(d?.type, c.op, d);
    this.changed.emit();
  }
  setOp(c: Condition, op: Operator): void {
    const prevKind = this.inputKind(c);
    c.op = op;
    if (this.inputKind(c) !== prevKind) c.value = defaultValue(this.def(c)?.type, op, this.def(c));
    this.changed.emit();
  }
  set(c: Condition, v: ConditionValue): void {
    c.value = v;
    this.changed.emit();
  }
  setList(c: Condition, text: string): void {
    c.value = text.split(',').map((s) => s.trim()).filter(Boolean);
    this.changed.emit();
  }
  setRange(c: Condition, i: number, v: string): void {
    const arr = Array.isArray(c.value) ? [...c.value] : ['', ''];
    arr[i] = v;
    c.value = (this.def(c)?.type === 'number' ? arr.map((x) => (x === '' ? x : Number(x))) : arr) as string[];
    this.changed.emit();
  }
  addCondition(): void {
    this.group().children.push(newCondition(this.catalog()[0]?.path ?? 'transaction.amount', 'eq', ''));
    const c = this.group().children.at(-1) as Condition;
    this.setField(c, c.field);
  }
  addGroup(): void {
    this.group().children.push(newGroup(this.group().match === 'all' ? 'any' : 'all', [newCondition('transaction.amount', 'gte', '')]));
    this.changed.emit();
  }
  removeAt(i: number): void {
    this.group().children.splice(i, 1);
    this.changed.emit();
  }
}
