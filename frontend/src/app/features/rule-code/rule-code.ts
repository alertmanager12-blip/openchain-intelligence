import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { Rule, RuleValidationError } from '../../core/models';
import { emptyRule } from '../../core/rules/rule-model';
import { ruleToYaml, yamlToRule } from '../../core/rules/rule-yaml';

@Component({
  selector: 'oci-rule-code',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Rules as code</h1>
          <p>Version rules next to your code. The YAML is plain data, with no anchors, tags or expressions, and the server validates it before storing it.</p>
        </div>
        <div class="row">
          <button class="btn" type="button" (click)="fileInput.click()">Import a file…</button>
          <input #fileInput type="file" accept=".yaml,.yml,text/yaml" class="sr-only" (change)="importFile($event)" aria-label="Import YAML file" />
          <button class="btn" type="button" (click)="exportAll()" [disabled]="busy()">Export all rules</button>
        </div>
      </div>

      <div class="layout">
        <section class="card stack">
          <label for="yaml-editor">Rule YAML</label>
          <div class="editor">
            <pre class="gutter" aria-hidden="true">{{ gutter() }}</pre>
            <textarea id="yaml-editor" class="code" spellcheck="false" autocapitalize="off" autocomplete="off" [ngModel]="text()" (ngModelChange)="text.set($event); result.set(null)"
              (keydown.tab)="indent($event)" aria-describedby="yaml-status"></textarea>
          </div>
          <div class="row">
            <button class="btn btn--primary" type="button" (click)="validate()" [disabled]="busy()">Validate on server</button>
            <button class="btn" type="button" (click)="importText()" [disabled]="busy() || local().errors.length > 0">Save (import)</button>
            <span id="yaml-status" class="small" [class.ok]="local().errors.length === 0" [class.bad]="local().errors.length > 0" aria-live="polite">
              Local check: {{ local().errors.length === 0 ? 'no issue found' : local().errors.length === 1 ? '1 issue to fix' : local().errors.length + ' issues to fix' }}
            </span>
          </div>
        </section>

        <aside class="stack">
          <section class="card">
            <h2>Validation</h2>
            @if (result(); as r) {
              <p [class.ok]="r.valid" [class.bad]="!r.valid"><strong>{{ r.source }}: {{ r.valid ? 'valid' : r.errors.length === 1 ? '1 error' : r.errors.length + ' errors' }}</strong></p>
              <ul class="errs">@for (e of r.errors; track $index) { <li><span class="mono">{{ where(e) }}</span> {{ e.message }}</li> }</ul>
            } @else {
              <ul class="errs">@for (e of local().errors; track $index) { <li><span class="mono">{{ where(e) }}</span> {{ e.message }}</li> } @empty { <li class="okli">No issue found locally. Validate on the server to be sure.</li> }</ul>
            }
            @if (error(); as e) { <p class="field-error" role="alert">{{ e.title }}: {{ e.detail }}</p>
              @for (f of e.fieldErrors; track $index) { <p class="field-error mono">{{ f.field }}: {{ f.message }}</p> } }
            @if (imported().length) { <p class="note" role="status">Saved: {{ importedNames() }}</p> }
          </section>
          <section class="card small">
            <h2>Reference</h2>
            <p>Groups: <span class="mono">all:</span> (AND) or <span class="mono">any:</span> (OR), nestable 4 levels.</p>
            <p>Condition: <span class="mono">field</span>, <span class="mono">op</span>, <span class="mono">value</span>. Amounts are exact decimals in quotes: <span class="mono">"100000.50"</span>.</p>
            <p>Durations: <span class="mono">30s 15m 1h 7d</span>. Several rules in one file are separated by <span class="mono">---</span>.</p>
          </section>
        </aside>
      </div>
    </div>
  `,
  styles: `
    .layout { display: grid; grid-template-columns: minmax(0, 1fr) 360px; gap: 16px; align-items: start; }
    @media (max-width: 1100px) { .layout { grid-template-columns: minmax(0, 1fr); } }
    .editor { display: flex; border: 1px solid var(--line); border-radius: 6px; overflow: hidden; background: var(--surface); }
    .editor:focus-within { border-color: var(--accent); }
    .gutter { margin: 0; padding: 8px 6px; min-width: 38px; text-align: right; color: var(--muted); background: var(--sunken); font: 13px/1.55 var(--mono); user-select: none; overflow: hidden; }
    .editor textarea { border: 0; border-radius: 0; outline: none; padding: 8px 10px; min-height: 520px; flex: 1; }
    .errs { margin: 0; padding-left: 18px; font-size: 12.5px; color: var(--danger); display: grid; gap: 4px; }
    .okli { color: var(--muted); list-style: none; margin-left: -18px; }
    .ok { color: var(--ok); } .bad { color: var(--danger); }
  `,
})
export class RuleCodePage {
  private readonly api = inject(ApiService);
  readonly text = signal(ruleToYaml({ ...emptyRule(), id: 'my-first-rule', name: 'My first rule' }));
  readonly local = computed(() => {
    const docs = this.text().split(/^---\s*$/m).filter((s) => s.trim());
    return { errors: docs.flatMap((d, i) => yamlToRule(d).errors.map((e) => (docs.length > 1 ? { ...e, path: `doc ${i + 1} ${e.path}` } : e))) };
  });
  readonly gutter = computed(() => Array.from({ length: this.text().split('\n').length }, (_, i) => i + 1).join('\n'));
  readonly result = signal<{ source: string; valid: boolean; errors: RuleValidationError[] } | null>(null);
  readonly busy = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly imported = signal<Rule[]>([]);
  readonly importedNames = computed(() => this.imported().map((r) => `${r.id} v${r.version}`).join(', '));

  where(e: RuleValidationError): string {
    return e.line ? `line ${e.line}` : e.path || '(document)';
  }

  indent(ev: Event): void {
    ev.preventDefault();
    const ta = ev.target as HTMLTextAreaElement;
    const { selectionStart: s, selectionEnd: e } = ta;
    ta.setRangeText('  ', s, e, 'end');
    this.text.set(ta.value);
  }

  validate(): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.validateRule(this.text()).subscribe({
      next: (r) => {
        this.result.set({ source: 'Server', valid: r.valid, errors: r.errors });
        this.busy.set(false);
      },
      error: (e: ApiError) => {
        this.error.set(e);
        this.busy.set(false);
      },
    });
  }

  importText(): void {
    this.busy.set(true);
    this.error.set(null);
    this.imported.set([]);
    this.api.importRules(this.text()).subscribe({
      next: (rules) => {
        this.imported.set(rules);
        this.busy.set(false);
      },
      error: (e: ApiError) => {
        this.error.set(e);
        this.busy.set(false);
      },
    });
  }

  importFile(ev: Event): void {
    const input = ev.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    if (file.size > 512 * 1024) {
      this.error.set(new ApiError(0, 'File too large', 'Rule files are limited to 512 KB.'));
      return;
    }
    file.text().then((t) => {
      this.text.set(t);
      this.result.set(null);
      input.value = '';
    });
  }

  exportAll(): void {
    this.busy.set(true);
    this.api.exportRules().subscribe({
      next: (yaml) => {
        const url = URL.createObjectURL(new Blob([yaml], { type: 'application/yaml' }));
        const a = document.createElement('a');
        a.href = url;
        a.download = `openchain-rules-${new Date().toISOString().slice(0, 10)}.yaml`;
        a.click();
        URL.revokeObjectURL(url);
        this.busy.set(false);
      },
      error: (e: ApiError) => {
        this.error.set(e);
        this.busy.set(false);
      },
    });
  }
}
