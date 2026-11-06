import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { EventTypeDef, Rule } from '../../core/models';
import { AVAILABLE_EVENT_TYPES, DEFAULT_FIELDS, EVENT_TYPES, RuleDraft, emptyRule } from '../../core/rules/rule-model';
import { ruleToYaml, validateDraft, yamlToRule } from '../../core/rules/rule-yaml';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { PageHeader } from '../../shared/page-header';
import { ActionsStep } from './actions-step';
import { ConditionGroupEditor } from './condition-group';
import { RuleStepNav, STEPS, StepId, stepOfPath } from './rule-steps';
import { ScopeStep } from './scope-step';
import { WindowStep } from './window-step';

/**
 * Rule builder as a stepper (Scope, Conditions, Window, Actions, Review). The draft stays a mutable RuleDraft
 * edited with ngModel: the recursive condition tree is mutated in place by oci-condition-group, which does not
 * map onto Signal Forms without rewriting core/rules.
 */
@Component({
  selector: 'oci-rule-builder',
  imports: [FormsModule, RouterLink, PageHeader, RuleStepNav, ScopeStep, WindowStep, ActionsStep, ConditionGroupEditor],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <oci-page-header heading="Rule builder" lede="Compose a rule without code. Rules are declarative data checked against a fixed set of operators, so nothing you type is ever executed.">
        <label class="open">Open a rule<select [ngModel]="loadedId()" (ngModelChange)="loadRule($event)">
          <option value="">New rule</option>@for (r of rules(); track r.id) { <option [value]="r.id">{{ r.name }}</option> }
        </select></label>
      </oci-page-header>

      <oci-rule-step-nav [(current)]="step" [issues]="issues()" />

      <div class="layout" [class.review]="step() === 'review'">
        <form class="panel-raised main" (submit)="$event.preventDefault(); next()" novalidate [attr.aria-labelledby]="'h-step'">
          <!-- the step bar above already shows the step number, title and hint -->
          <h2 id="h-step" class="sr-only">Step {{ index() + 1 }}: {{ current().label }}</h2>

          @switch (step()) {
            @case ('scope') { <oci-scope-step [draft]="d()" [errors]="errors()" [eventTypes]="eventTypes()" [idLocked]="!!loadedId()" (patch)="patch($event)" /> }
            @case ('conditions') {
              <p class="hint lead">Conditions are checked on each event in scope. Group them with all, any or none, up to four levels deep.</p>
              <oci-condition-group [group]="d().when" [catalog]="catalog()" [errors]="errors()" (changed)="touch()" />
            }
            @case ('window') { <oci-window-step [draft]="d()" [catalog]="catalog()" [errors]="errors()" (patch)="patch($event)" /> }
            @case ('actions') { <oci-actions-step [draft]="d()" [errors]="errors()" (patch)="patch($event)" /> }
            @case ('review') {
              <div class="well verdict" role="status">
                @if (errors().length === 0) { <strong class="ok">Ready to save.</strong> <span>The rule passes every local check. The server validates it again when you save.</span> }
                @else { <strong class="bad">{{ errors().length === 1 ? '1 issue to fix' : errors().length + ' issues to fix' }}</strong> <span>before this rule can be saved.</span> }
              </div>
              @if (errors().length) {
                <ul class="errs">@for (e of errors(); track $index) {
                  <li><span class="mono">{{ e.path }}</span> {{ e.message }} <button type="button" class="btn btn--sm btn--ghost" (click)="step.set(stepOf(e.path))">Fix in {{ stepLabel(e.path) }}</button></li>
                }</ul>
              }
              <pre class="code yaml-full" aria-label="Rule YAML">{{ yaml() }}</pre>
              @if (saveError(); as e) { <p class="field-error" role="alert">{{ e.title }}: {{ e.detail }}</p> }
            }
          }

          <div class="foot">
            @if (index() > 0) { <button class="btn" type="button" (click)="back()">Back</button> }
            <span class="spacer"></span>
            @if (step() === 'review') {
              <button class="btn" type="button" (click)="copy()">Copy YAML</button>
              <button class="btn" type="button" (click)="simulate()" [disabled]="errors().length > 0">Run simulation</button>
              <button class="btn btn--primary" type="button" (click)="save()" [disabled]="errors().length > 0 || saving()">{{ saving() ? 'Saving…' : loadedId() ? 'Save changes' : 'Create rule' }}</button>
            } @else {
              <button class="btn btn--primary" type="submit">{{ step() === 'actions' ? 'Review rule' : 'Next: ' + nextLabel() }}</button>
            }
          </div>
        </form>

        @if (step() !== 'review') {
          <aside class="card side" aria-labelledby="h-yaml">
            <div class="card-head"><h2 id="h-yaml">YAML preview</h2>
              <span class="small strong act" [class.ok]="errors().length === 0" [class.bad]="errors().length > 0">{{ errors().length === 0 ? 'Valid' : errors().length === 1 ? '1 issue' : errors().length + ' issues' }}</span></div>
            <pre class="code yaml" aria-label="Generated YAML">{{ yaml() }}</pre>
            <p class="small muted">This is exactly what gets saved. <a routerLink="/rules/code">Edit it as code instead</a>.</p>
          </aside>
        }
      </div>
    </div>
  `,
  styles: `
    .open { min-width: 220px; }
    .layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(300px, 400px); gap: 24px; align-items: start; }
    .layout.review { grid-template-columns: minmax(0, 1fr); }
    @media (max-width: 1100px) { .layout { grid-template-columns: minmax(0, 1fr); } }
    .main { display: grid; gap: 20px; }
    .lead { margin: -8px 0 0; }
    .foot { display: flex; gap: 8px; flex-wrap: wrap; padding-top: 16px; border-top: 1px solid var(--line-soft); }
    .side { position: sticky; top: 76px; display: grid; gap: 8px; }
    .act { margin-left: auto; }
    .yaml { max-height: 60vh; }
    .yaml-full { max-height: 70vh; }
    .verdict { display: flex; gap: 6px; flex-wrap: wrap; align-items: baseline; }
    .errs { margin: 0; padding: 0; list-style: none; display: grid; gap: 4px; font-size: 14px; }
    .errs li { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; color: var(--danger); }
  `,
})
export class RuleBuilderPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly toasts = inject(Toasts);
  /** ?rule=<id> opens that stored rule (links from Rules as code). */
  readonly rule = input<string>();

  readonly step = signal<StepId>('scope');
  readonly index = computed(() => STEPS.findIndex((s) => s.id === this.step()));
  readonly current = computed(() => STEPS[this.index()]);
  readonly nextLabel = computed(() => STEPS[this.index() + 1]?.label.toLowerCase() ?? '');

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

  stepLabel(path: string): string {
    return STEPS.find((s) => s.id === stepOfPath(path))!.label.toLowerCase();
  }

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
    else this.toasts.error('Cannot open this rule in the builder', 'Its YAML could not be parsed. Open it in Rules as code.');
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
        this.toasts.success(id ? 'Changes saved' : 'Rule created', `${r.id}, version ${r.version}`);
      },
      error: (e: ApiError) => {
        this.saving.set(false);
        this.saveError.set(e);
      },
    });
  }

  copy(): void {
    void navigator.clipboard?.writeText(this.yaml()).then(() => this.toasts.success('YAML copied')).catch(() => this.toasts.error('Could not copy', 'The browser refused clipboard access.'));
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
