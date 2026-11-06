import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { RuleValidationError } from '../../core/models';
import { emptyRule } from '../../core/rules/rule-model';
import { ruleToYaml, yamlToRule } from '../../core/rules/rule-yaml';
import { Toasts } from '../../core/toasts';
import { PageHeader } from '../../shared/page-header';
import { StoredRules } from './stored-rules';

/** Rules as code: a YAML editor with local and server validation, import / export, and the stored rules. */
@Component({
  selector: 'oci-rule-code',
  imports: [FormsModule, PageHeader, StoredRules],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <oci-page-header heading="Rules as code" lede="Version rules next to your code. The YAML is plain data, with no anchors, tags or expressions, and the server validates it before storing it.">
        <button class="btn" type="button" (click)="fileInput.click()">Import a file</button>
        <input #fileInput type="file" accept=".yaml,.yml,text/yaml" class="sr-only" (change)="importFile($event)" aria-label="Import YAML file" />
        <button class="btn" type="button" (click)="exportAll()" [disabled]="busy()">Export all rules</button>
      </oci-page-header>

      <section class="panel-raised readout-panel" aria-labelledby="h-val">
        <div class="card-head"><h2 id="h-val">Validation</h2>
          <div class="row act">
            <button class="btn btn--primary" type="button" (click)="validate()" [disabled]="busy()">Validate on server</button>
            <button class="btn" type="button" (click)="importText()" [disabled]="busy() || local().errors.length > 0">Save to server</button>
          </div></div>
        <p class="status-line">
          <span id="yaml-status" aria-live="polite" [class.bad]="local().errors.length > 0">Local check <strong>{{ local().errors.length === 0 ? 'no issue' : local().errors.length + (local().errors.length === 1 ? ' issue' : ' issues') }}</strong></span>
          <span [class.bad]="server() && !server()!.valid">Server validation <strong>@if (server(); as r) { {{ r.valid ? 'valid' : r.errors.length + (r.errors.length === 1 ? ' error' : ' errors') }} } @else { not run yet }</strong></span>
          <span><strong>{{ local().docs }}</strong> {{ local().docs === 1 ? 'rule' : 'rules' }} in this file, <strong>{{ lineCount() }}</strong> lines</span>
        </p>
        @if (shownErrors().length) {
          <ul class="errs">@for (e of shownErrors(); track $index) { <li><span class="mono">{{ where(e) }}</span> {{ e.message }}</li> }</ul>
        }
        @if (error(); as e) { <p class="field-error" role="alert">{{ e.title }}: {{ e.detail }}</p>
          @for (f of e.fieldErrors; track $index) { <p class="field-error mono">{{ f.field }}: {{ f.message }}</p> } }
      </section>

      <div class="split">
        <section class="card editor-card" aria-label="Editor">
          <label for="yaml-editor" class="sr-only">Rule YAML</label>
          <div class="editor">
            <pre class="gutter" aria-hidden="true">{{ gutter() }}</pre>
            <textarea id="yaml-editor" class="code" spellcheck="false" autocapitalize="off" autocomplete="off" [ngModel]="text()" (ngModelChange)="setText($event)"
              (keydown.tab)="indent($event)" aria-describedby="yaml-status"></textarea>
          </div>
        </section>
        <aside class="card small ref" aria-labelledby="h-ref">
          <h2 id="h-ref">Reference</h2>
          <p>Groups: <span class="mono">all:</span> (and) or <span class="mono">any:</span> (or), nestable four levels.</p>
          <p>Condition: <span class="mono">field</span>, <span class="mono">op</span>, <span class="mono">value</span>. Amounts are exact decimals in quotes: <span class="mono">"100000.50"</span>.</p>
          <p>Durations: <span class="mono">30s 15m 1h 7d</span>. Separate several rules in one file with <span class="mono">---</span>.</p>
        </aside>
      </div>

      <oci-stored-rules (loadYaml)="loadYaml($event)" />
    </div>
  `,
  styles: `
    .readout-panel { display: grid; gap: 14px; }
    .readout-panel .card-head { margin: 0; }
    .act { margin-left: auto; }
    .editor-card { padding: 0; overflow: hidden; }
    .editor { display: flex; background: var(--surface); }
    .gutter { margin: 0; padding: 12px 8px; min-width: 44px; text-align: right; color: var(--muted); background: var(--surface-2); border-right: 1px solid var(--line-soft); font: 13px/1.6 var(--mono); user-select: none; overflow: hidden; }
    .editor textarea { border: 0; border-radius: 0; box-shadow: none; padding: 12px 14px; min-height: 520px; flex: 1; }
    .editor textarea:focus { box-shadow: inset 0 0 0 2px color-mix(in srgb, var(--accent) 40%, transparent); }
    .errs { margin: 0; padding-left: 18px; font-size: 14px; color: var(--danger); display: grid; gap: 4px; }
    .ref p { color: var(--ink-2); }
  `,
})
export class RuleCodePage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  readonly text = signal(ruleToYaml({ ...emptyRule(), id: 'my-first-rule', name: 'My first rule' }));
  readonly local = computed(() => {
    const docs = this.text().split(/^---\s*$/m).filter((s) => s.trim());
    return { docs: docs.length, errors: docs.flatMap((d, i) => yamlToRule(d).errors.map((e) => (docs.length > 1 ? { ...e, path: `doc ${i + 1} ${e.path}` } : e))) };
  });
  readonly lineCount = computed(() => this.text().split('\n').length);
  readonly gutter = computed(() => Array.from({ length: this.lineCount() }, (_, i) => i + 1).join('\n'));
  readonly server = signal<{ valid: boolean; errors: RuleValidationError[] } | null>(null);
  /** Server errors when a server result exists for the current text, local ones otherwise. */
  readonly shownErrors = computed(() => this.server()?.errors ?? this.local().errors);
  readonly busy = signal(false);
  private readonly stored = viewChild(StoredRules);
  readonly error = signal<ApiError | null>(null);

  where(e: RuleValidationError): string {
    return e.line ? `line ${e.line}` : e.path || '(document)';
  }

  setText(t: string): void {
    this.text.set(t);
    this.server.set(null);
  }

  loadYaml(yaml: string): void {
    this.setText(yaml);
    this.toasts.success('YAML loaded into the editor');
  }

  indent(ev: Event): void {
    ev.preventDefault();
    const ta = ev.target as HTMLTextAreaElement;
    ta.setRangeText('  ', ta.selectionStart, ta.selectionEnd, 'end');
    this.setText(ta.value);
  }

  private call<T>(obs: Observable<T>, next: (v: T) => void): void {
    this.busy.set(true);
    this.error.set(null);
    obs.subscribe({
      next: (v) => {
        this.busy.set(false);
        next(v);
      },
      error: (e: ApiError) => {
        this.busy.set(false);
        this.error.set(e);
      },
    });
  }

  validate(): void {
    this.call(this.api.validateRule(this.text()), (r) => {
      this.server.set({ valid: r.valid, errors: r.errors });
      if (r.valid) this.toasts.success('Rule is valid', 'The server accepted the YAML.');
    });
  }

  importText(): void {
    this.call(this.api.importRules(this.text()), (rules) => {
      this.toasts.success(rules.length === 1 ? 'Rule saved' : `${rules.length} rules saved`, rules.map((r) => `${r.id} v${r.version}`).join(', '));
      this.stored()?.reload();
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
    void file.text().then((t) => {
      this.setText(t);
      input.value = '';
      this.toasts.success('File imported', `${file.name} is in the editor. Validate it, then save it to the server.`);
    });
  }

  exportAll(): void {
    this.call(this.api.exportRules(), (yaml) => {
      const url = URL.createObjectURL(new Blob([yaml], { type: 'application/yaml' }));
      const a = document.createElement('a');
      a.href = url;
      a.download = `openchain-rules-${new Date().toISOString().slice(0, 10)}.yaml`;
      a.click();
      URL.revokeObjectURL(url);
      this.toasts.success('Rules exported', a.download);
    });
  }
}
