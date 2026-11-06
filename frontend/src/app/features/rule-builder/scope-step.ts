import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EventTypeDef, NETWORKS, NetworkId, RuleValidationError, SEVERITIES } from '../../core/models';
import { EVIDENCE_LEVELS, RuleDraft } from '../../core/rules/rule-model';
import { sentence } from '../../shared/ui';

/** Step 1: identity of the rule and what it watches (networks, event types). */
@Component({
  selector: 'oci-scope-step',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid meta">
      <label class="wide">Name<input name="name" [ngModel]="draft().name" (ngModelChange)="patch.emit({ name: $event })" required [attr.aria-invalid]="err('name') ? true : null" />
        @if (err('name'); as m) { <span class="field-error">{{ m }}</span> }</label>
      <label>Identifier<input name="id" class="mono" [ngModel]="draft().id" (ngModelChange)="patch.emit({ id: $event })" [disabled]="idLocked()" [attr.aria-invalid]="err('id') ? true : null" />
        @if (err('id'); as m) { <span class="field-error">{{ m }}</span> } @else if (idLocked()) { <span class="hint">A stored rule keeps its identifier.</span> }</label>
      <label>Severity<select name="sev" [ngModel]="draft().severity" (ngModelChange)="patch.emit({ severity: $event })">@for (s of severities; track s) { <option [value]="s">{{ sentence(s) }}</option> }</select></label>
      <label>Evidence level<select name="ev" [ngModel]="draft().evidenceLevel" (ngModelChange)="patch.emit({ evidenceLevel: $event })">@for (e of evidenceLevels; track e) { <option [value]="e">{{ sentence(e) }}</option> }</select></label>
      <label>Version<input name="ver" [ngModel]="draft().version" (ngModelChange)="patch.emit({ version: $event })" placeholder="1.0.0" [attr.aria-invalid]="err('version') ? true : null" />
        @if (err('version'); as m) { <span class="field-error">{{ m }}</span> }</label>
      <label>Author<input name="author" [ngModel]="draft().author" (ngModelChange)="patch.emit({ author: $event })" [attr.aria-invalid]="err('author') ? true : null" />
        @if (err('author'); as m) { <span class="field-error">{{ m }}</span> }</label>
    </div>
    <label>Description<input name="desc" [ngModel]="draft().description" (ngModelChange)="patch.emit({ description: $event })" placeholder="What this rule is for, for the analyst who receives the alert" /></label>
    <fieldset><legend>Networks</legend>
      <div class="opts">@for (n of networks; track n) {
        <label class="check cap"><input type="checkbox" [checked]="draft().networks.includes(n)" (change)="toggleNetwork(n)" [attr.aria-describedby]="noSource.includes(n) ? 'net-nosource' : null" />{{ n }}
          @if (noSource.includes(n)) { <span class="nd">no data source yet</span> }</label>
      }</div>
      <p class="hint" id="net-nosource">Bitcoin and Solana are accepted in rules, but this version has no data source for them yet, so a rule scoped only to them never fires.</p>
      @if (onlyNoSource()) { <p class="note note--warn">This rule only watches networks without a data source. It will not fire until one is added.</p> }
      @if (errUnder('networks'); as m) { <span class="field-error">{{ m }}</span> }
    </fieldset>
    <fieldset><legend>Event types</legend>
      <div class="opts">@for (t of eventTypes(); track t.type) {
        <label class="check" [class.na]="!t.available"><input type="checkbox" [checked]="draft().eventTypes.includes(t.type)" (change)="toggleEvent(t.type)" />{{ sentence(t.type) }}@if (!t.available) { <span class="hint">not produced yet</span> }</label>
      }</div>
      @if (errUnder('event_types'); as m) { <span class="field-error">{{ m }}</span> }
    </fieldset>
  `,
  styles: `
    :host { display: grid; gap: 20px; }
    .meta { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
    .wide { grid-column: span 2; }
    @media (max-width: 720px) { .meta { grid-template-columns: minmax(0, 1fr); } .wide { grid-column: auto; } }
    .opts { display: flex; flex-wrap: wrap; gap: 8px 20px; }
    .nd { font-size: 12px; color: var(--muted); text-transform: none; }
    .hint { margin: 6px 0 0; }
    .na { color: var(--ink-2); }
  `,
})
export class ScopeStep {
  readonly draft = input.required<RuleDraft>();
  readonly errors = input<RuleValidationError[]>([]);
  readonly eventTypes = input<EventTypeDef[]>([]);
  readonly idLocked = input(false);
  readonly patch = output<Partial<RuleDraft>>();

  readonly networks = NETWORKS;
  /** Networks in the core Network enum without a BlockchainProvider adapter yet (only EVM and TRON adapters exist). */
  readonly noSource: NetworkId[] = ['bitcoin', 'solana'];
  readonly onlyNoSource = computed(() => this.draft().networks.length > 0 && this.draft().networks.every((n) => this.noSource.includes(n)));
  readonly severities = SEVERITIES;
  readonly evidenceLevels = EVIDENCE_LEVELS;
  readonly sentence = sentence;

  err(path: string): string | null {
    return this.errors().find((e) => e.path === path)?.message ?? null;
  }
  errUnder(prefix: string): string | null {
    return this.errors().find((e) => e.path === prefix || e.path.startsWith(prefix + '['))?.message ?? null;
  }
  toggleNetwork(n: NetworkId): void {
    const s = this.draft().networks;
    this.patch.emit({ networks: s.includes(n) ? s.filter((x) => x !== n) : [...s, n] });
  }
  toggleEvent(t: string): void {
    const s = this.draft().eventTypes;
    this.patch.emit({ eventTypes: s.includes(t) ? s.filter((x) => x !== t) : [...s, t] });
  }
}
