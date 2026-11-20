import { BreakpointObserver } from '@angular/cdk/layout';
import { STEPPER_GLOBAL_OPTIONS } from '@angular/cdk/stepper';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatProgressBar } from '@angular/material/progress-bar';
import { MatStepperModule } from '@angular/material/stepper';
import { Router, RouterLink } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { ApiError, apiErrorText } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { EventTypeDef, Rule, RuleValidationError } from '../../core/models';
import { AVAILABLE_EVENT_TYPES, DEFAULT_FIELDS, EVENT_TYPES, RuleDraft, emptyRule } from '../../core/rules/rule-model';
import { ruleErrorText, ruleToYaml, validateDraft, yamlToRule } from '../../core/rules/rule-yaml';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { ruleText } from '../../shared/rule-text';
import { MAT_BUTTONS, MAT_FORM } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { ActionsStep } from './actions-step';
import { ConditionGroupEditor } from './condition-group';
import { STEPS, StepId, stepOfPath } from './rule-steps';
import { ScopeStep } from './scope-step';
import { WindowStep } from './window-step';

/**
 * Rule builder as a mat-stepper (Scope, Conditions, Window, Actions, Review), non-linear, with the issue count per step. The draft stays a mutable RuleDraft
 * edited with ngModel: the recursive condition tree is mutated in place by oci-condition-group, which does not
 * map onto Signal Forms without rewriting core/rules.
 */
@Component({
  selector: 'oci-rule-builder',
  imports: [RouterLink, PageHeader, MatStepperModule, MatProgressBar, ...MAT_BUTTONS, ...MAT_FORM, ScopeStep, WindowStep, ActionsStep, ConditionGroupEditor],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Steps report their issues (error state) but stay reachable in any order (linear=false).
  providers: [{ provide: STEPPER_GLOBAL_OPTIONS, useValue: { showError: true, displayDefaultIndicatorType: false } }],
  template: `
    <div class="page">
      <oci-page-header class="stack-acts" [heading]="i18n.t('rules.builder.title')" [lede]="i18n.t('rules.builder.lede')">
        <mat-form-field class="open" subscriptSizing="dynamic">
          <mat-select [value]="loadedId()" (valueChange)="loadRule($event)" [attr.aria-label]="i18n.t('rules.builder.open')">
            <mat-option value="">{{ i18n.t('rules.builder.new') }}</mat-option>@for (r of rules(); track r.id) { <mat-option [value]="r.id">{{ ruleLabel(r) }}</mat-option> }
          </mat-select>
        </mat-form-field>
      </oci-page-header>

      <div class="layout" [class.review]="step() === 'review'">
        <form class="panel-raised main" (submit)="$event.preventDefault(); next()" novalidate [attr.aria-labelledby]="'h-step'">
          <h2 id="h-step" class="sr-only">{{ i18n.t('rules.builder.stepHeading', { n: index() + 1, label: i18n.t(current().label) }) }}</h2>
          <mat-stepper [linear]="false" [selectedIndex]="index()" (selectionChange)="step.set(steps[$event.selectedIndex].id)" [orientation]="narrow() ? 'vertical' : 'horizontal'"
            [attr.aria-label]="i18n.t('rules.builder.stepsAria')" animationDuration="0">
            <!-- step icons: the number, or the number in the error colour when the step has issues (no icon font) -->
            <ng-template matStepperIcon="edit" let-index="index">{{ index + 1 }}</ng-template>
            <ng-template matStepperIcon="done" let-index="index">{{ index + 1 }}</ng-template>
            <ng-template matStepperIcon="error" let-index="index">{{ index + 1 }}</ng-template>
            @for (s of steps; track s.id) {
              <mat-step [hasError]="!!issues()[s.id]" [errorMessage]="issueText(s.id)" [aria-label]="stepAria(s.id, $index)" [completed]="false">
                <ng-template matStepLabel>{{ i18n.t(s.label) }}@if (issues()[s.id]; as c) { <span class="iss" aria-hidden="true">{{ c }}</span> }</ng-template>
                <ng-template matStepContent>
                  <div class="step-body">
                    <p class="step-hint">{{ i18n.t(s.hint) }}</p>
                    @switch (s.id) {
                      @case ('scope') { <oci-scope-step [draft]="d()" [errors]="errors()" [eventTypes]="eventTypes()" [idLocked]="!!loadedId()" (patch)="patch($event)" /> }
                      @case ('conditions') {
                        <p class="hint lead">{{ i18n.t('rules.builder.conditionsLead') }}</p>
                        <oci-condition-group [group]="d().when" [catalog]="catalog()" [errors]="errors()" (changed)="touch()" />
                      }
                      @case ('window') { <oci-window-step [draft]="d()" [catalog]="catalog()" [errors]="errors()" (patch)="patch($event)" /> }
                      @case ('actions') { <oci-actions-step [draft]="d()" [errors]="errors()" (patch)="patch($event)" /> }
                      @case ('review') {
                        <div class="well verdict" role="status">
                          @if (errors().length === 0) { <strong class="ok">{{ i18n.t('rules.builder.ready') }}</strong> <span>{{ i18n.t('rules.builder.readyBody') }}</span> }
                          @else { <strong class="bad">{{ i18n.t('rules.builder.issuesToFix', { n: errors().length }) }}</strong> <span>{{ i18n.t('rules.builder.beforeSave') }}</span> }
                        </div>
                        @if (errors().length) {
                          <ul class="errs">@for (e of errors(); track $index) {
                            <li><span class="mono">{{ e.path }}</span> {{ errText(e) }} <button matButton class="sm" type="button" (click)="step.set(stepOf(e.path))">{{ i18n.t('rules.builder.fixIn', { step: stepLabel(e.path) }) }}</button></li>
                          }</ul>
                        }
                        <pre class="code yaml-full" [attr.aria-label]="i18n.t('rules.builder.yamlAria')">{{ yaml() }}</pre>
                        @if (saveError(); as e) { <p class="field-error" role="alert">{{ apiError(e) }}</p> }
                      }
                    }
                    <div class="foot">
                      @if (index() > 0) { <button matButton="outlined" type="button" (click)="back()">{{ i18n.t('rules.builder.back') }}</button> }
                      <span class="spacer"></span>
                      @if (step() === 'review') {
                        <button matButton="outlined" type="button" (click)="copy()">{{ i18n.t('rules.builder.copy') }}</button>
                        <button matButton="outlined" type="button" (click)="simulate()" [disabled]="errors().length > 0">{{ i18n.t('rules.builder.simulate') }}</button>
                        <button matButton="filled" type="button" (click)="save()" [disabled]="errors().length !== 0 || saving()">{{ i18n.t(saving() ? 'rules.builder.saving' : loadedId() ? 'rules.builder.saveChanges' : 'rules.builder.create') }}</button>
                      } @else {
                        <button matButton="filled" type="submit">{{ step() === 'actions' ? i18n.t('rules.builder.reviewRule') : i18n.t('rules.builder.next', { step: nextLabel() }) }}</button>
                      }
                    </div>
                    @if (saving()) { <mat-progress-bar mode="indeterminate" [attr.aria-label]="i18n.t('rules.builder.savingAria')" /> }
                  </div>
                </ng-template>
              </mat-step>
            }
          </mat-stepper>
        </form>

        @if (step() !== 'review') {
          <aside class="card side" aria-labelledby="h-yaml">
            <div class="card-head"><h2 id="h-yaml">{{ i18n.t('rules.builder.preview') }}</h2>
              <span class="small strong act" [class.ok]="errors().length === 0" [class.bad]="errors().length > 0">{{ errors().length === 0 ? i18n.t('rules.builder.valid') : i18n.t('rules.builder.issues', { n: errors().length }) }}</span></div>
            <pre class="code yaml" [attr.aria-label]="i18n.t('rules.builder.generatedAria')">{{ yaml() }}</pre>
            <p class="small muted">{{ i18n.t('rules.builder.exactly') }} <a routerLink="/rules/code">{{ i18n.t('rules.builder.editAsCode') }}</a>.</p>
          </aside>
        }
      </div>
    </div>
  `,
  styles: `
    .open { width: 220px; }
    .layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(300px, 400px); gap: 24px; align-items: start; }
    .layout.review { grid-template-columns: minmax(0, 1fr); }
    @media (max-width: 1100px) { .layout { grid-template-columns: minmax(0, 1fr); } }
    .main { padding: 0 20px 20px; }
    .step-body { display: grid; gap: 18px; padding-top: 18px; }
    .step-hint { margin: 0 0 -6px; font: 500 13px var(--font); color: var(--ink); }
    .lead { margin: 0; }
    .iss { display: inline-block; margin-left: 6px; font: 500 11px/18px var(--font); color: var(--danger); background: color-mix(in srgb, var(--risk-critical) var(--tint), var(--surface)); padding: 0 7px; border-radius: 10px; vertical-align: 1px; }
    .foot { display: flex; gap: 8px; flex-wrap: wrap; padding-top: 16px; border-top: 1px solid var(--line-soft); }
    .side { position: sticky; top: calc(var(--topbar-h) + 16px); display: grid; gap: 8px; }
    .act { margin-left: auto; }
    .yaml { max-height: 60vh; }
    .yaml-full { max-height: 70vh; }
    .verdict { display: flex; gap: 6px; flex-wrap: wrap; align-items: baseline; }
    .errs { margin: 0; padding: 0; list-style: none; display: grid; gap: 4px; font-size: 13px; }
    .errs li { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; color: var(--danger); }
    /* flat stepper: small numbered discs, a hairline under the header, no ripple or hover wash */
    mat-stepper { background: transparent; }
    :host ::ng-deep .mat-horizontal-stepper-header-container { margin: 0 -20px; padding: 0 8px; border-bottom: 1px solid var(--line-soft); }
    :host ::ng-deep .mat-horizontal-content-container { padding: 0; }
    :host ::ng-deep .mat-step-icon { width: 20px; height: 20px; font: 600 11px var(--font); box-shadow: inset 0 0 0 1px var(--line); }
    :host ::ng-deep .mat-step-icon-selected { box-shadow: none; }
    :host ::ng-deep .mat-step-icon-state-error { background: color-mix(in srgb, var(--risk-critical) var(--tint), var(--surface)); color: var(--danger); box-shadow: none; }
    :host ::ng-deep .mat-step-header .mat-step-header-ripple, :host ::ng-deep .mat-step-header:hover { background: transparent; }
    :host ::ng-deep .mat-step-header:hover .mat-step-label { color: var(--ink); }
    :host ::ng-deep .mat-step-label-error .mat-step-sub-label-error { display: none; }
    :host ::ng-deep .mat-horizontal-stepper-header { padding: 0 12px; border-radius: 0; }
    :host ::ng-deep .mat-step-header .mat-step-icon { margin-right: 8px; }
    :host ::ng-deep .mat-stepper-horizontal-line { min-width: 12px; border-top-color: var(--line); }
    @media (max-width: 720px) { .main { padding: 8px 8px 16px; } .open { width: 150px; } }
  `,
})
export class RuleBuilderPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly toasts = inject(Toasts);
  protected readonly i18n = inject(I18nService);
  /** ?rule=<id> opens that stored rule (links from Rules as code). */
  readonly rule = input<string>();

  readonly steps = STEPS;
  readonly step = signal<StepId>('scope');
  /** Vertical stepper on narrow screens: five horizontal headers do not fit 390px. */
  readonly narrow = toSignal(inject(BreakpointObserver).observe('(max-width: 720px)').pipe(map((s) => s.matches)), { initialValue: false });
  readonly index = computed(() => STEPS.findIndex((s) => s.id === this.step()));
  readonly current = computed(() => STEPS[this.index()]);
  readonly nextLabel = computed(() => { const n = STEPS[this.index() + 1]; return n ? this.i18n.t(n.label).toLocaleLowerCase(this.i18n.lang()) : ''; });

  private readonly catalogRes = apiResource({ params: () => ({}), stream: () => this.api.fieldCatalog().pipe(catchError(() => of(DEFAULT_FIELDS))) });
  private readonly eventTypesRes = apiResource({ params: () => ({}), stream: () => this.api.eventTypes().pipe(catchError(() => of([] as EventTypeDef[]))) });
  private readonly rulesRes = apiResource({ params: () => ({}), stream: () => this.api.rules().pipe(catchError(() => of({ items: [] as Rule[], nextCursor: null }))) });
  readonly catalog = computed(() => (this.catalogRes.value()?.length ? this.catalogRes.value()! : DEFAULT_FIELDS));
  /** Live availability from GET /rules/event-types; the static list only as a fallback. */
  readonly eventTypes = computed<EventTypeDef[]>(() => this.eventTypesRes.value()?.length
    ? this.eventTypesRes.value()!
    : EVENT_TYPES.map((type) => ({ type, available: AVAILABLE_EVENT_TYPES.includes(type) })));
  readonly rules = computed(() => this.rulesRes.value()?.items ?? []);

  readonly d = signal<RuleDraft>(emptyRule());
  readonly loadedId = signal('');
  readonly saving = signal(false);
  readonly saveError = signal<ApiError | null>(null);

  readonly yaml = computed(() => ruleToYaml(this.d()));
  readonly errors = computed(() => validateDraft(this.d(), this.catalog()));
  readonly issues = computed(() => {
    const out: Partial<Record<StepId, number>> = {};
    for (const e of this.errors()) {
      const s = stepOfPath(e.path);
      out[s] = (out[s] ?? 0) + 1;
    }
    return out;
  });
  readonly stepOf = stepOfPath;

  private openedParam: string | null = null;

  constructor() {
    effect(() => {
      const id = this.rule();
      const rules = this.rules();
      if (!id || id === this.openedParam || !rules.some((r) => r.id === id)) return;
      this.openedParam = id;
      untracked(() => this.loadRule(id));
    });
  }

  issueText(id: StepId): string {
    return this.i18n.t('rules.builder.issues', { n: this.issues()[id] ?? 0 });
  }

  /** Accessible name of a step header: its number, label and issue count. */
  stepAria(id: StepId, i: number): string {
    const n = this.issues()[id];
    return `${i + 1}. ${this.i18n.t(STEPS[i].label)}${n ? ', ' + this.issueText(id) : ''}`;
  }

  stepLabel(path: string): string {
    return this.i18n.t(STEPS.find((s) => s.id === stepOfPath(path))!.label).toLocaleLowerCase(this.i18n.lang());
  }
  protected errText(e: RuleValidationError): string { return ruleErrorText(e, this.i18n.lang()); }
  protected apiError(e: ApiError): string { return apiErrorText(e, this.i18n.lang()); }
  /** A stored rule's name in the console's language. */
  protected ruleLabel(r: Rule): string { return ruleText(r, this.i18n.lang()).name; }

  next(): void {
    const n = STEPS[this.index() + 1];
    if (n) this.step.set(n.id);
  }
  back(): void {
    const p = STEPS[this.index() - 1];
    if (p) this.step.set(p.id);
  }

  /** Nested editors mutate in place; a new top-level reference re-runs YAML and validation. */
  touch(): void {
    this.d.update((d) => ({ ...d }));
  }
  patch(p: Partial<RuleDraft>): void {
    this.d.update((d) => ({ ...d, ...p }));
  }

  loadRule(id: string): void {
    this.loadedId.set(id);
    this.saveError.set(null);
    if (!id) {
      this.d.set(emptyRule());
      return;
    }
    const r = this.rules().find((x) => x.id === id);
    const parsed = r ? yamlToRule(r.yaml, this.catalog()) : null;
    if (parsed?.draft) this.d.set(parsed.draft);
    else this.toasts.error(this.i18n.t('rules.builder.toast.cannotOpen'), this.i18n.t('rules.builder.toast.cannotOpenDetail'));
  }

  save(): void {
    if (this.errors().length) return;
    this.saving.set(true);
    this.saveError.set(null);
    const id = this.loadedId();
    (id ? this.api.updateRule(id, this.yaml()) : this.api.createRule(this.yaml())).subscribe({
      next: (r) => {
        this.saving.set(false);
        this.loadedId.set(r.id);
        this.rulesRes.update((p) => ({ items: [r, ...(p?.items ?? []).filter((x) => x.id !== r.id)], nextCursor: p?.nextCursor ?? null }));
        this.toasts.success(this.i18n.t(id ? 'rules.builder.toast.saved' : 'rules.builder.toast.created'), this.i18n.t('rules.builder.toast.savedDetail', { id: r.id, version: r.version }));
      },
      error: (e: ApiError) => {
        this.saving.set(false);
        this.saveError.set(e);
      },
    });
  }

  copy(): void {
    void navigator.clipboard?.writeText(this.yaml()).then(() => this.toasts.success(this.i18n.t('rules.builder.toast.copied'))).catch(() => this.toasts.error(this.i18n.t('rules.builder.toast.cannotCopy'), this.i18n.t('rules.builder.toast.clipboardRefused')));
  }

  simulate(): void {
    try {
      sessionStorage.setItem('oci.sim.yaml', this.yaml());
    } catch {
      /* simulator falls back to the rule picker */
    }
    this.router.navigate(['/rules/simulator'], { queryParams: { draft: 1 } });
  }
}
