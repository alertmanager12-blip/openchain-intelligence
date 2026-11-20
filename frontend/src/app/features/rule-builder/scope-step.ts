import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { I18nService, hasKey } from '../../core/i18n/i18n.service';
import { EventTypeDef, EvidenceLevel, NETWORKS, NetworkId, RuleValidationError, SEVERITIES } from '../../core/models';
import { EVIDENCE_LEVELS, RuleDraft } from '../../core/rules/rule-model';
import { ruleErrorText } from '../../core/rules/rule-yaml';
import { evidenceDetail } from '../../shared/levels';
import { netLabel, sentence, severityName } from '../../shared/format';
import { MAT_FORM } from '../../shared/material';

/** Step 1: identity of the rule and what it watches (networks, event types). */
@Component({
  selector: 'oci-scope-step',
  imports: [FormsModule, MatCheckboxModule, ...MAT_FORM],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid meta">
      <mat-form-field class="wide">
        <mat-label>{{ i18n.t('rules.scope.name') }}</mat-label>
        <input matInput name="name" [ngModel]="draft().name" (ngModelChange)="patch.emit({ name: $event })" required [ociInvalid]="!!err('name')" />
        @if (err('name'); as m) { <mat-error>{{ errText(m) }}</mat-error> }
      </mat-form-field>
      <mat-form-field>
        <mat-label>{{ i18n.t('rules.scope.id') }}</mat-label>
        <input matInput name="id" class="mono" [ngModel]="draft().id" (ngModelChange)="patch.emit({ id: $event })" [disabled]="idLocked()" [ociInvalid]="!!err('id')" />
        @if (err('id'); as m) { <mat-error>{{ errText(m) }}</mat-error> } @else if (idLocked()) { <mat-hint>{{ i18n.t('rules.scope.idLocked') }}</mat-hint> }
      </mat-form-field>
      <mat-form-field>
        <mat-label>{{ i18n.t('rules.scope.severity') }}</mat-label>
        <mat-select [value]="draft().severity" (valueChange)="patch.emit({ severity: $event })">@for (s of severities; track s) { <mat-option [value]="s">{{ severityText(s) }}</mat-option> }</mat-select>
      </mat-form-field>
      <mat-form-field>
        <mat-label>{{ i18n.t('rules.scope.evidence') }}</mat-label>
        <mat-select [value]="draft().evidenceLevel" (valueChange)="patch.emit({ evidenceLevel: $event })">@for (e of evidenceLevels; track e) { <mat-option [value]="e">{{ evidenceText(e) }}</mat-option> }</mat-select>
      </mat-form-field>
      <mat-form-field>
        <mat-label>{{ i18n.t('rules.scope.version') }}</mat-label>
        <input matInput name="ver" [ngModel]="draft().version" (ngModelChange)="patch.emit({ version: $event })" placeholder="1.0.0" [ociInvalid]="!!err('version')" />
        @if (err('version'); as m) { <mat-error>{{ errText(m) }}</mat-error> }
      </mat-form-field>
      <mat-form-field>
        <mat-label>{{ i18n.t('rules.scope.author') }}</mat-label>
        <input matInput name="author" [ngModel]="draft().author" (ngModelChange)="patch.emit({ author: $event })" [ociInvalid]="!!err('author')" />
        @if (err('author'); as m) { <mat-error>{{ errText(m) }}</mat-error> }
      </mat-form-field>
    </div>
    <mat-form-field>
      <mat-label>{{ i18n.t('rules.scope.description') }}</mat-label>
      <input matInput name="desc" [ngModel]="draft().description" (ngModelChange)="patch.emit({ description: $event })" [placeholder]="i18n.t('rules.scope.descriptionPlaceholder')" />
    </mat-form-field>
    <fieldset><legend>{{ i18n.t('rules.scope.networks') }}</legend>
      <div class="opts">@for (n of networks; track n) {
        <mat-checkbox [checked]="draft().networks.includes(n)" (change)="toggleNetwork(n)" [aria-describedby]="noSource.includes(n) ? 'net-nosource' : ''">{{ netLabel(n) }}
          @if (noSource.includes(n)) { <span class="nd">{{ i18n.t('rules.scope.noSource') }}</span> }</mat-checkbox>
      }</div>
      <p class="hint" id="net-nosource">{{ i18n.t('rules.scope.noSourceHint') }}</p>
      @if (onlyNoSource()) { <p class="note note--warn">{{ i18n.t('rules.scope.onlyNoSource') }}</p> }
      @if (errUnder('networks'); as m) { <span class="field-error">{{ errText(m) }}</span> }
    </fieldset>
    <fieldset><legend>{{ i18n.t('rules.scope.eventTypes') }}</legend>
      <div class="opts">@for (t of eventTypes(); track t.type) {
        <mat-checkbox [checked]="draft().eventTypes.includes(t.type)" (change)="toggleEvent(t.type)" [class.na]="!t.available">{{ eventTypeText(t.type) }}@if (!t.available) { <span class="nd">{{ i18n.t('rules.scope.notProduced') }}</span> }</mat-checkbox>
      }</div>
      @if (errUnder('event_types'); as m) { <span class="field-error">{{ errText(m) }}</span> }
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
  protected readonly i18n = inject(I18nService);
  protected severityText(s: string): string { return severityName(s, this.i18n.lang()); }
  protected evidenceText(e: EvidenceLevel): string { const t = evidenceDetail(e, this.i18n.lang()); return t.charAt(0).toUpperCase() + t.slice(1); }
  protected eventTypeText(t: string): string { const k = `rules.eventType.${t}`; return hasKey(k) ? this.i18n.t(k) : sentence(t); }
  protected errText(e: RuleValidationError): string { return ruleErrorText(e, this.i18n.lang()); }

  err(path: string): RuleValidationError | null {
    return this.errors().find((e) => e.path === path) ?? null;
  }
  errUnder(prefix: string): RuleValidationError | null {
    return this.errors().find((e) => e.path === prefix || e.path.startsWith(prefix + '[')) ?? null;
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
