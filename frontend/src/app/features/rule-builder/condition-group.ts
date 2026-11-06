import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { FieldDef, FieldType } from '../../core/models';
import {
  Condition, ConditionGroup, ConditionValue, OPERATORS_BY_TYPE, OPERATOR_LABELS, Operator, availabilityNote, newCondition, newGroup,
} from '../../core/rules/rule-model';

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
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="group" [class.nested]="depth() > 1" role="group" [attr.aria-label]="(group().match === 'all' ? 'All' : group().match === 'any' ? 'Any' : 'None') + ' of the following conditions'">
      <div class="row head">
        <span class="kw">{{ depth() === 1 ? 'When' : 'Group' }}</span>
        <div class="seg" role="radiogroup" aria-label="Group logic">
          <button type="button" role="radio" [attr.aria-checked]="group().match === 'all'" [attr.aria-pressed]="group().match === 'all'" (click)="setMatch('all')">All match</button>
          <button type="button" role="radio" [attr.aria-checked]="group().match === 'any'" [attr.aria-pressed]="group().match === 'any'" (click)="setMatch('any')">Any matches</button>
          <button type="button" role="radio" [attr.aria-checked]="group().match === 'not'" [attr.aria-pressed]="group().match === 'not'" (click)="setMatch('not')">None matches</button>
        </div>
        <span class="spacer"></span>
        @if (removable()) { <button class="btn btn--sm btn--ghost btn--danger" type="button" (click)="remove.emit()" aria-label="Remove group">Remove group</button> }
      </div>

      @for (ch of group().children; track ch.id; let i = $index; let first = $first) {
        @if (!first) { <div class="joiner">{{ group().match === 'any' ? 'or' : group().match === 'not' ? 'nor' : 'and' }}</div> }
        @if (ch.kind === 'group') {
          <oci-condition-group [group]="ch" [catalog]="catalog()" [depth]="depth() + 1" [removable]="true" [errors]="errors()" [path]="childPath(i)" (changed)="changed.emit()" (remove)="removeAt(i)" />
        } @else {
          <div class="cond" [class.invalid]="hasError(childPath(i))">
            <label class="f">Field
              <select [ngModel]="ch.field" (ngModelChange)="setField(ch, $event)">
                @for (f of catalog(); track f.path) { <option [value]="f.path" [title]="f.description">{{ f.path }} ({{ f.type }}){{ note(f.available) }}</option> }
              </select>
            </label>
            <label class="o">Operator
              <select [ngModel]="ch.op" (ngModelChange)="setOp(ch, $event)">
                @for (op of ops(ch); track op) { <option [value]="op">{{ opLabel[op] }}</option> }
              </select>
            </label>
            <div class="v">
              @switch (inputKind(ch)) {
                @case ('none') { <span class="small muted">no value needed</span> }
                @case ('bool') {
                  <label>Value<select [ngModel]="ch.value" (ngModelChange)="set(ch, $event)"><option [ngValue]="true">true</option><option [ngValue]="false">false</option></select></label>
                }
                @case ('enum') {
                  <label>Value<select [ngModel]="ch.value" (ngModelChange)="set(ch, $event)">@for (o of def(ch)?.options ?? []; track o) { <option [value]="o">{{ o }}</option> }</select></label>
                }
                @case ('label') {
                  <label>Category<select [ngModel]="ch.value" (ngModelChange)="set(ch, $event)">@for (o of labelCats; track o) { <option [value]="o">{{ o }}</option> }</select></label>
                }
                @case ('list') {
                  <label>Values (comma separated)<input [ngModel]="listText(ch)" (ngModelChange)="setList(ch, $event)" [placeholder]="def(ch)?.options?.join(', ') ?? 'a, b, c'" /></label>
                }
                @case ('range') {
                  <div class="row nowrap">
                    <label>Min<input [attr.inputmode]="numeric(ch) ? 'decimal' : null" [ngModel]="rangeAt(ch, 0)" (ngModelChange)="setRange(ch, 0, $event)" /></label>
                    <label>Max<input [attr.inputmode]="numeric(ch) ? 'decimal' : null" [ngModel]="rangeAt(ch, 1)" (ngModelChange)="setRange(ch, 1, $event)" /></label>
                  </div>
                }
                @case ('number') {
                  <label>Value<input type="number" [ngModel]="ch.value" (ngModelChange)="set(ch, $event === null || $event === '' ? null : +$event)" /></label>
                }
                @default {
                  <label>Value<input [attr.inputmode]="def(ch)?.type === 'amount' ? 'decimal' : null" [ngModel]="ch.value" (ngModelChange)="set(ch, $event)"
                    [placeholder]="def(ch)?.type === 'amount' ? 'exact decimal, e.g. 100000.50' : def(ch)?.type === 'timestamp' ? '2026-01-31T00:00:00Z' : ''" /></label>
                }
              }
            </div>
            <button class="btn btn--sm btn--ghost x" type="button" (click)="removeAt(i)" [disabled]="group().children.length === 1 && depth() === 1" [attr.aria-label]="'Remove condition ' + (i + 1)">Remove</button>
            @for (e of errorsFor(childPath(i)); track $index) { <p class="field-error full">{{ e }}</p> }
            @if (def(ch); as d) { <p class="small muted full">{{ d.description }}</p> }
          </div>
        }
      }
      @if (hasOwnError()) { <p class="field-error">{{ ownErrors().join('; ') }}</p> }
      <div class="row add">
        <button class="btn btn--sm" type="button" (click)="addCondition()">Add condition</button>
        @if (depth() < maxDepth) { <button class="btn btn--sm" type="button" (click)="addGroup()">Add group</button> }
      </div>
    </div>
  `,
  styles: `
    .group { border: 1px solid var(--line); border-radius: var(--r-well); padding: 12px 14px; display: grid; gap: 10px; background: var(--surface); }
    .nested { background: var(--surface-2); border-left: 3px solid var(--accent); }
    .kw { font: 600 15px var(--font-display); color: var(--ink); min-width: 48px; }
    .joiner { font: 600 13px var(--font-display); color: var(--accent); padding-left: 12px; }
    .cond { display: grid; grid-template-columns: minmax(160px, 1.3fr) minmax(120px, 0.8fr) minmax(160px, 1.4fr) auto; gap: 8px; align-items: end; padding: 8px 0 10px; border-bottom: 1px solid var(--line-soft); }
    .cond.invalid { border-bottom-color: var(--risk-critical); }
    .cond select, .cond input { width: 100%; }
    .full { grid-column: 1 / -1; margin: 0; }
    .x { align-self: end; }
    @media (max-width: 720px) { .cond { grid-template-columns: minmax(0, 1fr); } }
  `,
})
export class ConditionGroupEditor {
  readonly group = input.required<ConditionGroup>();
  readonly catalog = input.required<FieldDef[]>();
  readonly depth = input(1);
  readonly removable = input(false);
  readonly path = input('when');
  /** Validation errors keyed by path, from the page. */
  readonly errors = input<{ path: string; message: string }[]>([]);
  readonly changed = output<void>();
  readonly remove = output<void>();

  readonly opLabel = OPERATOR_LABELS;
  readonly labelCats = LABEL_CATEGORIES;
  readonly maxDepth = MAX_DEPTH;
  readonly note = availabilityNote;
  readonly ownErrors = computed(() => this.errors().filter((e) => e.path === this.path()).map((e) => e.message));
  readonly hasOwnError = computed(() => this.ownErrors().length > 0);

  childPath(i: number): string {
    return `${this.path()}.${this.group().match}[${i}]`;
  }
  errorsFor(p: string): string[] {
    return this.errors().filter((e) => e.path === p || e.path.startsWith(p + '.')).map((e) => e.message);
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
