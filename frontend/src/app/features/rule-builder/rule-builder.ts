import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { FieldDef, NETWORKS, NetworkId, Rule, SEVERITIES } from '../../core/models';
import { ACTION_TYPES, AGGREGATES, AVAILABLE_EVENT_TYPES, ActionType, DEFAULT_FIELDS, EVENT_TYPES, EVIDENCE_LEVELS, RuleDraft, emptyRule } from '../../core/rules/rule-model';
import { ruleToYaml, validateDraft, yamlToRule } from '../../core/rules/rule-yaml';
import { UI } from '../../shared/ui';
import { ConditionGroupEditor } from './condition-group';

@Component({
  selector: 'oci-rule-builder',
  imports: [FormsModule, RouterLink, ConditionGroupEditor, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Alert Builder</h1>
          <p>Compose a rule without code. Rules are declarative data checked against a fixed set of operators: nothing you type is ever executed. The YAML on the right is exactly what gets saved.</p>
        </div>
        <div class="row">
          <label>Load rule<select [ngModel]="loadedId()" (ngModelChange)="loadRule($event)">
            <option value="">New rule</option>@for (r of rules(); track r.id) { <option [value]="r.id">{{ r.name }}</option> }
          </select></label>
        </div>
      </div>

      <div class="layout">
        <form class="stack" (submit)="$event.preventDefault(); save()" novalidate>
          <section class="card stack">
            <h2>Rule</h2>
            <div class="grid meta">
              <label>Name<input name="name" [ngModel]="d().name" (ngModelChange)="patch({ name: $event })" required [attr.aria-invalid]="err('name') ? true : null" />
                @if (err('name'); as m) { <span class="field-error">{{ m }}</span> }</label>
              <label>Identifier<input name="id" class="mono" [ngModel]="d().id" (ngModelChange)="patch({ id: $event })" [disabled]="!!loadedId()" />
                @if (err('id'); as m) { <span class="field-error">{{ m }}</span> }</label>
              <label>Severity<select name="sev" [ngModel]="d().severity" (ngModelChange)="patch({ severity: $event })">@for (s of severities; track s) { <option [value]="s">{{ s }}</option> }</select></label>
              <label>Evidence level<select name="ev" [ngModel]="d().evidenceLevel" (ngModelChange)="patch({ evidenceLevel: $event })">@for (e of evidenceLevels; track e) { <option [value]="e">{{ e }}</option> }</select></label>
              <label>Version<input name="ver" [ngModel]="d().version" (ngModelChange)="patch({ version: $event })" placeholder="1.0.0" /></label>
              <label>Author<input name="author" [ngModel]="d().author" (ngModelChange)="patch({ author: $event })" /></label>
            </div>
            <label>Description<input name="desc" [ngModel]="d().description" (ngModelChange)="patch({ description: $event })" placeholder="What this rule is for, for the analyst who receives the alert" /></label>
            <fieldset><legend>Networks</legend><div class="row">
              @for (n of networks; track n) { <label class="check"><input type="checkbox" [checked]="d().networks.includes(n)" (change)="toggleNetwork(n)" />{{ n }}</label> }
            </div>@if (err('networks'); as m) { <span class="field-error">{{ m }}</span> }</fieldset>
            <fieldset><legend>Event types</legend><div class="row">
              @for (t of eventTypes; track t) { <label class="check"><input type="checkbox" [checked]="d().eventTypes.includes(t)" (change)="toggleEvent(t)" />{{ t }}{{ availableEvents.includes(t) ? '' : ' (not yet available)' }}</label> }
            </div>@if (err('event_types'); as m) { <span class="field-error">{{ m }}</span> }</fieldset>
          </section>

          <section class="card stack">
            <h2>Conditions</h2>
            <oci-condition-group [group]="d().when" [catalog]="catalog()" [errors]="errors()" (changed)="touch()" />
          </section>

          <section class="card stack">
            <div class="row"><h2 class="m0">Window aggregation</h2><span class="spacer"></span>
              <label class="check"><input type="checkbox" [checked]="!!d().window" (change)="toggleWindow()" />Aggregate over a time window</label></div>
            @if (d().window; as w) {
              <p class="small muted">Fires when the aggregate of matching events over the window crosses the threshold, e.g. "sum of amount per sender over 1h > 1,000,000".</p>
              <div class="grid win">
                <label>Aggregate<select name="agg" [ngModel]="w.aggregate" (ngModelChange)="patchWindow({ aggregate: $event })">@for (a of aggregates; track a) { <option [value]="a">{{ a }}</option> }</select></label>
                @if (w.aggregate !== 'count') {
                  <label>Of field<select name="wf" [ngModel]="w.field" (ngModelChange)="patchWindow({ field: $event })">@for (f of catalog(); track f.path) { <option [value]="f.path">{{ f.path }}</option> }</select></label>
                }
                <label>Group by<select name="gb" [ngModel]="w.groupBy[0] ?? ''" (ngModelChange)="patchWindow({ groupBy: $event ? [$event, ...w.groupBy.slice(1)] : [] })"><option value="">(none)</option>@for (f of catalog(); track f.path) { <option [value]="f.path">{{ f.path }}</option> }</select></label>
                <label>Over<input name="wd" [ngModel]="w.duration" (ngModelChange)="patchWindow({ duration: $event })" placeholder="1h" /></label>
                <label>Is<select name="wo" [ngModel]="w.op" (ngModelChange)="patchWindow({ op: $event })">@for (o of windowOps; track o) { <option [value]="o">{{ o }}</option> }</select></label>
                <label>Threshold<input name="wv" inputmode="decimal" [ngModel]="w.value" (ngModelChange)="patchWindow({ value: $event })" /></label>
              </div>
              @for (m of errsUnder('window'); track $index) { <span class="field-error">{{ m }}</span> }
            }
          </section>

          <section class="card stack">
            <h2>Actions</h2>
            @for (a of d().actions; track $index; let i = $index) {
              <div class="row">
                <label>Type<select [ngModel]="a.type" (ngModelChange)="setAction(i, { type: $event })" [name]="'at' + i">@for (t of actionTypes; track t) { <option [value]="t">{{ t }}</option> }</select></label>
                @if (a.type !== 'alert') { <label class="grow">Connector id<input [name]="'tg' + i" [ngModel]="a.target" (ngModelChange)="setAction(i, { target: $event })" placeholder="wh-ops" /></label> }
                <button class="btn btn--sm btn--ghost" type="button" (click)="removeAction(i)" aria-label="Remove action">✕</button>
              </div>
              @if (err('actions[' + i + '].target'); as m) { <span class="field-error">{{ m }}</span> }
            }
            <div class="row">
              <button class="btn btn--sm" type="button" (click)="addAction()">+ Action</button>
              <span class="spacer"></span>
              <label>Cooldown<input name="cd" class="cd" [ngModel]="d().cooldown" (ngModelChange)="patch({ cooldown: $event })" placeholder="15m" /></label>
              <label class="check"><input type="checkbox" [checked]="d().enabled" (change)="patch({ enabled: !d().enabled })" />Enabled</label>
            </div>
            @if (err('cooldown'); as m) { <span class="field-error">{{ m }}</span> }
            <p class="small muted">Cooldown suppresses repeated alerts for the same rule and subject during that period.</p>
          </section>
        </form>

        <aside class="card side">
          <div class="row"><h2 class="m0">YAML preview</h2><span class="spacer"></span>
            <span class="small" [class.ok]="errors().length === 0" [class.bad]="errors().length > 0">{{ errors().length === 0 ? 'Valid' : errors().length + ' issue(s)' }}</span></div>
          <pre class="code yaml" aria-label="Generated YAML">{{ yaml() }}</pre>
          @if (errors().length) {
            <ul class="errs">@for (e of errors(); track $index) { <li><span class="mono">{{ e.path }}</span>: {{ e.message }}</li> }</ul>
          }
          @if (saveError(); as e) { <p class="field-error" role="alert">{{ e.title }}: {{ e.detail }}</p> }
          @if (saved(); as s) { <p class="note" role="status">Saved {{ s.id }} (version {{ s.version }}).</p> }
          <div class="row">
            <button class="btn btn--primary" type="button" (click)="save()" [disabled]="errors().length > 0 || saving()">{{ saving() ? 'Saving…' : loadedId() ? 'Save changes' : 'Create rule' }}</button>
            <button class="btn" type="button" (click)="copy()">Copy YAML</button>
            <button class="btn" type="button" (click)="simulate()" [disabled]="errors().length > 0">Simulate</button>
          </div>
          <a class="small" routerLink="/rules/code">Edit as code instead →</a>
        </aside>
      </div>
    </div>
  `,
  styles: `
    .layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(320px, 420px); gap: 16px; align-items: start; }
    @media (max-width: 1100px) { .layout { grid-template-columns: 1fr; } }
    .meta { grid-template-columns: 2fr 1.4fr 1fr; }
    .win { grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); }
    @media (max-width: 720px) { .meta { grid-template-columns: 1fr; } }
    .side { position: sticky; top: 72px; display: grid; gap: 12px; }
    .yaml { max-height: 55vh; }
    .errs { margin: 0; padding-left: 18px; color: var(--danger); font-size: 12.5px; }
    .ok { color: var(--ok); font-weight: 600; } .bad { color: var(--danger); font-weight: 600; }
    .m0 { margin: 0; } .grow { flex: 1; min-width: 200px; } .cd { width: 90px; }
  `,
})
export class RuleBuilderPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);

  readonly networks = NETWORKS;
  readonly severities = SEVERITIES;
  readonly evidenceLevels = EVIDENCE_LEVELS;
  readonly eventTypes = EVENT_TYPES;
  readonly availableEvents = AVAILABLE_EVENT_TYPES;
  readonly aggregates = AGGREGATES;
  readonly actionTypes = ACTION_TYPES;
  readonly windowOps = ['gt', 'gte'];

  readonly d = signal<RuleDraft>(emptyRule());
  readonly catalog = signal<FieldDef[]>(DEFAULT_FIELDS);
  readonly rules = signal<Rule[]>([]);
  readonly loadedId = signal('');
  readonly saving = signal(false);
  readonly saveError = signal<ApiError | null>(null);
  readonly saved = signal<Rule | null>(null);

  readonly yaml = computed(() => ruleToYaml(this.d()));
  readonly errors = computed(() => validateDraft(this.d(), this.catalog()));

  constructor() {
    this.api.fieldCatalog().pipe(catchError(() => of(DEFAULT_FIELDS))).subscribe((c) => this.catalog.set(c.length ? c : DEFAULT_FIELDS));
    this.api.rules().pipe(catchError(() => of({ items: [] as Rule[], nextCursor: null }))).subscribe((p) => this.rules.set(p.items));
  }

  err(path: string): string | null {
    return this.errors().find((e) => e.path === path)?.message ?? null;
  }
  errsUnder(prefix: string): string[] {
    return this.errors().filter((e) => e.path.startsWith(prefix)).map((e) => e.message);
  }

  /** Nested editors mutate in place; a new top-level reference re-runs YAML and validation. */
  touch(): void {
    this.d.update((d) => ({ ...d }));
    this.saved.set(null);
  }
  patch(p: Partial<RuleDraft>): void {
    this.d.update((d) => ({ ...d, ...p }));
    this.saved.set(null);
  }
  patchWindow(p: Partial<NonNullable<RuleDraft['window']>>): void {
    const w = this.d().window;
    if (w) this.patch({ window: { ...w, ...p } });
  }
  toggleWindow(): void {
    this.patch({ window: this.d().window ? null : { duration: '1h', aggregate: 'sum', field: 'transaction.amount', groupBy: ['transaction.from'], op: 'gt', value: '1000000' } });
  }
  toggleNetwork(n: NetworkId): void {
    const s = this.d().networks;
    this.patch({ networks: s.includes(n) ? s.filter((x) => x !== n) : [...s, n] });
  }
  toggleEvent(t: string): void {
    const s = this.d().eventTypes;
    this.patch({ eventTypes: s.includes(t) ? s.filter((x) => x !== t) : [...s, t] });
  }
  setAction(i: number, p: { type?: ActionType; target?: string }): void {
    this.patch({ actions: this.d().actions.map((a, j) => (j === i ? { ...a, ...p } : a)) });
  }
  addAction(): void {
    this.patch({ actions: [...this.d().actions, { type: 'webhook', target: '' }] });
  }
  removeAction(i: number): void {
    this.patch({ actions: this.d().actions.filter((_, j) => j !== i) });
  }

  loadRule(id: string): void {
    this.loadedId.set(id);
    this.saved.set(null);
    this.saveError.set(null);
    if (!id) {
      this.d.set(emptyRule());
      return;
    }
    const r = this.rules().find((x) => x.id === id);
    const parsed = r ? yamlToRule(r.yaml, this.catalog()) : null;
    if (parsed?.draft) this.d.set(parsed.draft);
    else this.saveError.set(new ApiError(0, 'Cannot open this rule in the builder', 'Its YAML could not be parsed. Use Alert as Code.'));
  }

  save(): void {
    if (this.errors().length) return;
    this.saving.set(true);
    this.saveError.set(null);
    const id = this.loadedId();
    (id ? this.api.updateRule(id, this.yaml()) : this.api.createRule(this.yaml())).subscribe({
      next: (r) => {
        this.saving.set(false);
        this.saved.set(r);
        this.loadedId.set(r.id);
        this.rules.update((rs) => [r, ...rs.filter((x) => x.id !== r.id)]);
      },
      error: (e: ApiError) => {
        this.saving.set(false);
        this.saveError.set(e);
      },
    });
  }

  copy(): void {
    void navigator.clipboard?.writeText(this.yaml()).catch(() => undefined);
  }

  simulate(): void {
    try {
      sessionStorage.setItem('oci.sim.yaml', this.yaml());
    } catch {
      /* simulator falls back to rule picker */
    }
    this.router.navigate(['/rules/simulator'], { queryParams: { draft: 1 } });
  }
}
