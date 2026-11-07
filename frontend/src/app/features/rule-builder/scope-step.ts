import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { EventTypeDef, NETWORKS, NetworkId, RuleValidationError, SEVERITIES } from '../../core/models';
import { EVIDENCE_LEVELS, RuleDraft } from '../../core/rules/rule-model';
import { netLabel } from '../../shared/format';
import { MAT_FORM } from '../../shared/material';
import { sentence } from '../../shared/ui';

/** Step 1: identity of the rule and what it watches (networks, event types). */
@Component({
  selector: 'oci-scope-step',
  imports: [FormsModule, MatCheckboxModule, ...MAT_FORM],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid meta">
      <mat-form-field class="wide">
        <mat-label>Name</mat-label>
        <input matInput name="name" [ngModel]="draft().name" (ngModelChange)="patch.emit({ name: $event })" required [ociInvalid]="!!err('name')" />
        @if (err('name'); as m) { <mat-error>{{ m }}</mat-error> }
      </mat-form-field>
      <mat-form-field>
        <mat-label>Identifier</mat-label>
        <input matInput name="id" class="mono" [ngModel]="draft().id" (ngModelChange)="patch.emit({ id: $event })" [disabled]="idLocked()" [ociInvalid]="!!err('id')" />
        @if (err('id'); as m) { <mat-error>{{ m }}</mat-error> } @else if (idLocked()) { <mat-hint>A stored rule keeps its identifier.</mat-hint> }
      </mat-form-field>
      <mat-form-field>
        <mat-label>Severity</mat-label>
        <mat-select [value]="draft().severity" (valueChange)="patch.emit({ severity: $event })">@for (s of severities; track s) { <mat-option [value]="s">{{ sentence(s) }}</mat-option> }</mat-select>
      </mat-form-field>
      <mat-form-field>
        <mat-label>Evidence level</mat-label>
        <mat-select [value]="draft().evidenceLevel" (valueChange)="patch.emit({ evidenceLevel: $event })">@for (e of evidenceLevels; track e) { <mat-option [value]="e">{{ sentence(e) }}</mat-option> }</mat-select>
      </mat-form-field>
      <mat-form-field>
        <mat-label>Version</mat-label>
        <input matInput name="ver" [ngModel]="draft().version" (ngModelChange)="patch.emit({ version: $event })" placeholder="1.0.0" [ociInvalid]="!!err('version')" />
        @if (err('version'); as m) { <mat-error>{{ m }}</mat-error> }
      </mat-form-field>
      <mat-form-field>
        <mat-label>Author</mat-label>
        <input matInput name="author" [ngModel]="draft().author" (ngModelChange)="patch.emit({ author: $event })" [ociInvalid]="!!err('author')" />
        @if (err('author'); as m) { <mat-error>{{ m }}</mat-error> }
      </mat-form-field>
    </div>
    <mat-form-field>
      <mat-label>Description</mat-label>
      <input matInput name="desc" [ngModel]="draft().description" (ngModelChange)="patch.emit({ description: $event })" placeholder="What this rule is for, for the analyst who receives the alert" />
    </mat-form-field>
    <fieldset><legend>Networks</legend>
      <div class="opts">@for (n of networks; track n) {
        <mat-checkbox [checked]="draft().networks.includes(n)" (change)="toggleNetwork(n)" [aria-describedby]="noSource.includes(n) ? 'net-nosource' : ''">{{ netLabel(n) }}
          @if (noSource.includes(n)) { <span class="nd">no data source yet</span> }</mat-checkbox>
      }</div>
      <p class="hint" id="net-nosource">Bitcoin and Solana are accepted in rules, but this version has no data source for them yet, so a rule scoped only to them never fires.</p>
      @if (onlyNoSource()) { <p class="note note--warn">This rule only watches networks without a data source. It will not fire until one is added.</p> }
      @if (errUnder('networks'); as m) { <span class="field-error">{{ m }}</span> }
    </fieldset>
    <fieldset><legend>Event types</legend>
      <div class="opts">@for (t of eventTypes(); track t.type) {
        <mat-checkbox [checked]="draft().eventTypes.includes(t.type)" (change)="toggleEvent(t.type)" [class.na]="!t.available">{{ sentence(t.type) }}@if (!t.available) { <span class="nd">not produced yet</span> }</mat-checkbox>
      }</div>
      @if (errUnder('event_types'); as m) { <span class="field-error">{{ m }}</span> }
    </fieldset>
  `,
  styles: `
    :host { display: grid; gap: 16px; }
    .meta { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px 16px; }
    .wide { grid-column: span 2; }
    @media (max-width: 720px) { .meta { grid-template-columns: minmax(0, 1fr); } .wide { grid-column: auto; } }
    .opts { display: flex; flex-wrap: wrap; gap: 0 12px; margin-left: -8px; }
    .nd { font-size: 12px; color: var(--muted); text-transform: none; margin-left: 6px; }
    .hint { margin: 2px 0 0; }
    .na { --mat-checkbox-label-text-color: var(--ink-2); }
  `,
})
export class ScopeStep {
  readonly draft = input.required<RuleDraft>();
  readonly errors = input<RuleValidationError[]>([]);
  readonly eventTypes = input<EventTypeDef[]>([]);
  readonly idLocked = input(false);
  readonly patch = output<Partial<RuleDraft>>();

  readonly networks = NETWORKS;
  readonly netLabel = netLabel;
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
