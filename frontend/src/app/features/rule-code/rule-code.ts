import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { MatProgressBar } from '@angular/material/progress-bar';
import { Observable } from 'rxjs';
import { ApiError, apiErrorText } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { fmtNumber } from '../../core/i18n/format';
import { RuleValidationError } from '../../core/models';
import { emptyRule } from '../../core/rules/rule-model';
import { ruleErrorText, ruleErrorWhere, ruleToYaml, yamlToRule } from '../../core/rules/rule-yaml';
import { Toasts } from '../../core/toasts';
import { PageHeader } from '../../shared/page-header';
import { StoredRules } from './stored-rules';

/** Rules as code: a YAML editor with local and server validation, import / export, and the stored rules. */
@Component({
  selector: 'oci-rule-code',
  imports: [FormsModule, MatButton, MatProgressBar, PageHeader, StoredRules],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <oci-page-header class="stack-acts" [heading]="i18n.t('rules.code.title')" [lede]="i18n.t('rules.code.lede')">
        <button matButton="outlined" type="button" (click)="fileInput.click()">{{ i18n.t('rules.code.import') }}</button>
        <input #fileInput type="file" accept=".yaml,.yml,text/yaml" class="sr-only" (change)="importFile($event)" [attr.aria-label]="i18n.t('rules.code.importAria')" />
        <button matButton="outlined" type="button" (click)="exportAll()" [disabled]="busy()">{{ i18n.t('rules.code.exportAll') }}</button>
      </oci-page-header>

      <section class="panel-raised readout-panel" aria-labelledby="h-val">
        <div class="card-head"><h2 id="h-val">{{ i18n.t('rules.code.validation') }}</h2>
          <div class="row act">
            <button matButton="filled" type="button" (click)="validate()" [disabled]="busy()">{{ i18n.t('rules.code.validate') }}</button>
            <button matButton="outlined" type="button" (click)="importText()" [disabled]="busy() || local().errors.length > 0">{{ i18n.t('rules.code.save') }}</button>
          </div></div>
        <p class="status-line">
          <span id="yaml-status" aria-live="polite" [class.bad]="local().errors.length > 0">{{ i18n.t('rules.code.localCheck') }} <strong>{{ local().errors.length === 0 ? i18n.t('rules.code.noIssue') : i18n.t('rules.builder.issues', { n: local().errors.length }) }}</strong></span>
          <span [class.bad]="server() && !server()!.valid">{{ i18n.t('rules.code.serverValidation') }} <strong>@if (server(); as r) { {{ r.valid ? i18n.t('rules.code.valid') : i18n.t('rules.code.errors', { n: r.errors.length }) }} } @else { {{ i18n.t('rules.code.notRun') }} }</strong></span>
          <span><strong>{{ num(local().docs) }}</strong> {{ i18n.t('rules.code.inFile', { n: local().docs }) }} <strong>{{ num(lineCount()) }}</strong> {{ i18n.t('rules.code.lines', { n: lineCount() }) }}</span>
        </p>
        @if (busy()) { <mat-progress-bar mode="indeterminate" [attr.aria-label]="i18n.t('rules.code.waiting')" /> }
        @if (shownErrors().length) {
          <ul class="errs">@for (e of shownErrors(); track $index) { <li><span class="mono">{{ where(e) }}</span> {{ errText(e) }}</li> }</ul>
        }
        @if (error(); as e) { <p class="field-error" role="alert">{{ apiError(e) }}</p> }
        @if (tooLarge()) { <p class="field-error" role="alert">{{ i18n.t('rules.code.tooLarge') }}</p> }
      </section>

      <div class="split">
        <section class="card editor-card" [attr.aria-label]="i18n.t('rules.code.editor')">
          <label for="yaml-editor" class="sr-only">{{ i18n.t('rules.code.yamlLabel') }}</label>
          <div class="editor">
            <pre class="gutter" aria-hidden="true">{{ gutter() }}</pre>
            <textarea id="yaml-editor" class="code" spellcheck="false" autocapitalize="off" autocomplete="off" [ngModel]="text()" (ngModelChange)="setText($event)"
              (keydown.tab)="indent($event)" aria-describedby="yaml-status"></textarea>
          </div>
        </section>
        <aside class="card small ref" aria-labelledby="h-ref">
          <h2 id="h-ref">{{ i18n.t('rules.code.reference') }}</h2>
          <p>{{ i18n.t('rules.code.refGroups') }}</p>
          <p>{{ i18n.t('rules.code.refCondition', { example: '"100000.50"' }) }}</p>
          <p>{{ i18n.t('rules.code.refDurations', { durations: '30s 15m 1h 7d', separator: '---' }) }}</p>
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
    .gutter { margin: 0; padding: 12px 8px; min-width: 44px; text-align: right; color: var(--muted); background: var(--surface-2); border-right: 1px solid var(--line-soft); font: 12px/1.65 var(--mono); letter-spacing: -0.01em; user-select: none; overflow: hidden; }
    .editor textarea { min-height: 520px; flex: 1; }
    .editor textarea:focus { box-shadow: inset 0 0 0 2px color-mix(in srgb, var(--accent) 40%, transparent); }
    .errs { margin: 0; padding-left: 18px; font-size: 13px; color: var(--danger); display: grid; gap: 4px; }
    .ref p { color: var(--ink-2); }
  `,
})
export class RuleCodePage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  protected readonly i18n = inject(I18nService);
  readonly text = signal(ruleToYaml({ ...emptyRule(), id: 'my-first-rule', name: 'My first rule' }));
  readonly local = computed(() => {
    const docs = this.text().split(/^---\s*$/m).filter((s) => s.trim());
    return { docs: docs.length, errors: docs.flatMap((d, i) => yamlToRule(d).errors.map((e) => (docs.length > 1 ? { ...e, doc: i + 1 } : e))) };
  });
  readonly lineCount = computed(() => this.text().split('\n').length);
  readonly gutter = computed(() => Array.from({ length: this.lineCount() }, (_, i) => i + 1).join('\n'));
  readonly server = signal<{ valid: boolean; errors: RuleValidationError[] } | null>(null);
  /** Server errors when a server result exists for the current text, local ones otherwise. */
  readonly shownErrors = computed(() => this.server()?.errors ?? this.local().errors);
  readonly busy = signal(false);
  private readonly stored = viewChild(StoredRules);
  readonly error = signal<ApiError | null>(null);
  readonly tooLarge = signal(false);

  protected num(n: number): string { return fmtNumber(n, this.i18n.lang()); }
  protected where(e: RuleValidationError): string { return ruleErrorWhere(e, this.i18n.lang()); }
  protected errText(e: RuleValidationError): string { return ruleErrorText(e, this.i18n.lang()); }
  protected apiError(e: ApiError): string { return apiErrorText(e, this.i18n.lang()); }

  setText(t: string): void {
    this.text.set(t);
    this.server.set(null);
  }

  loadYaml(yaml: string): void {
    this.setText(yaml);
    this.toasts.success(this.i18n.t('rules.code.toast.loaded'));
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
    this.tooLarge.set(false);
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
      if (r.valid) this.toasts.success(this.i18n.t('rules.code.toast.valid'), this.i18n.t('rules.code.toast.validDetail'));
    });
  }

  importText(): void {
    this.call(this.api.importRules(this.text()), (rules) => {
      this.toasts.success(this.i18n.t('rules.code.toast.saved', { n: rules.length }), rules.map((r) => `${r.id} v${r.version}`).join(', '));
      this.stored()?.reload();
    });
  }

  importFile(ev: Event): void {
    const input = ev.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    if (file.size > 512 * 1024) {
      this.tooLarge.set(true);
      return;
    }
    void file.text().then((t) => {
      this.tooLarge.set(false);
      this.setText(t);
      input.value = '';
      this.toasts.success(this.i18n.t('rules.code.toast.imported'), this.i18n.t('rules.code.toast.importedDetail', { name: file.name }));
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
      this.toasts.success(this.i18n.t('rules.code.toast.exported'), a.download);
    });
  }
}
