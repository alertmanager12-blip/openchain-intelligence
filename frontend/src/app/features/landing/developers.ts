import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { I18nService, Key } from '../../core/i18n/i18n.service';
import { Availability } from './landing-content';
import { publicLinks } from './public-links';

/**
 * Developer section. The request matches the real contract (GET /api/v1/wallets/{address}/risk?network=, X-API-Key,
 * RiskScoreDto field names); the values in the response are illustrative.
 */
@Component({
  selector: 'oci-cs-developers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: block; }
    .dev-grid { display: grid; grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr); gap: 56px; align-items: start; }
    ul { list-style: none; margin: 32px 0 0; padding: 0; }
    li { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 2px 16px; padding: 14px 0; border-top: 1px solid var(--cs-line); }
    li strong { font-size: 15px; font-weight: 600; }
    li p { grid-column: 1 / -1; color: var(--cs-muted); font-size: 14px; line-height: 1.5; }
    .links { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 28px; }
    .nolinks { margin-top: 24px; color: var(--cs-faint); font-size: 14px; }
    .code { border: 1px solid var(--cs-line-2); border-radius: 12px; background: #05080c; overflow: hidden; position: sticky; top: 88px; }
    .code-head { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-bottom: 1px solid var(--cs-line); color: var(--cs-muted); font-size: 13px; }
    .code-head .sp { flex: 1; }
    .copy { height: 28px; padding: 0 10px; border: 1px solid var(--cs-line-2); border-radius: 6px; background: none; color: var(--cs-muted); font: 500 12px var(--cs-font); cursor: pointer; }
    .copy:hover { color: var(--cs-text); }
    pre { margin: 0; padding: 18px; overflow-x: auto; font: 400 13px/1.65 var(--cs-mono); color: #c9d4df; tab-size: 2; }
    pre + pre { border-top: 1px solid var(--cs-line); }
    /* the code samples are bound with [innerHTML]: their spans carry no component attribute, hence ::ng-deep */
    pre .c { color: var(--cs-faint); } pre ::ng-deep .k { color: var(--cs-flow); } pre ::ng-deep .s { color: var(--cs-accent); } pre ::ng-deep .n { color: var(--cs-warn); }
    @media (max-width: 960px) { .dev-grid { grid-template-columns: 1fr; gap: 40px; } .code { position: static; } }
  `,
  template: `
    <section class="cs-section" id="developers" aria-labelledby="dev-title">
      <div class="cs-wrap dev-grid">
        <div>
          <h2 class="cs-h2" id="dev-title">{{ i18n.t('landing.dev.title') }}</h2>
          <p class="cs-lede">{{ i18n.t('landing.dev.lede') }}</p>
          <ul>
            @for (f of features; track f.title) {
              <li><strong>{{ i18n.t(f.title) }}</strong><span class="cs-status cs-status--{{ f.status }}">{{ i18n.t(label[f.status]) }}</span><p [innerHTML]="i18n.t(f.detail)"></p></li>
            }
          </ul>
          @if (links.repository; as r) {
            <div class="links">
              <a class="cs-btn" [href]="links.docs" rel="noopener" target="_blank">{{ i18n.t('landing.dev.docs') }}</a>
              <a class="cs-btn" [href]="links.contributing" rel="noopener" target="_blank">{{ i18n.t('landing.dev.guide') }}</a>
              <a class="cs-btn" [href]="r" rel="noopener" target="_blank">{{ i18n.t('landing.dev.repo') }}</a>
            </div>
          } @else {
            <p class="nolinks">{{ i18n.t('landing.dev.nolinks') }}</p>
          }
        </div>

        <figure class="code" style="margin: 0" aria-labelledby="code-cap">
          <div class="code-head"><span id="code-cap">{{ i18n.t('landing.dev.codeCaption') }}</span><span class="sp"></span><span class="cs-illus">{{ i18n.t('landing.dev.illus') }}</span>
            <button class="copy" type="button" (click)="copy()">{{ copied() ? i18n.t('landing.dev.copied') : i18n.t('landing.dev.copy') }}</button></div>
          <pre><span class="c">{{ i18n.t('landing.dev.comment') }}</span>
<span [innerHTML]="requestHtml"></span></pre>
          <pre [innerHTML]="responseHtml"></pre>
        </figure>
      </div>
    </section>
  `,
})
export class DevelopersSection {
  protected readonly i18n = inject(I18nService);
  protected readonly links = publicLinks();
  protected readonly copied = signal(false);
  protected readonly label: Record<Availability, Key> = { available: 'landing.status.available', partial: 'landing.status.partial', planned: 'landing.status.planned' };
  protected readonly features: readonly { title: Key; detail: Key; status: Availability }[] = [
    { title: 'landing.dev.rest.t', status: 'available', detail: 'landing.dev.rest.d' },
    { title: 'landing.dev.openapi.t', status: 'available', detail: 'landing.dev.openapi.d' },
    { title: 'landing.dev.webhooks.t', status: 'available', detail: 'landing.dev.webhooks.d' },
    { title: 'landing.dev.rules.t', status: 'available', detail: 'landing.dev.rules.d' },
    { title: 'landing.dev.selfhost.t', status: 'available', detail: 'landing.dev.selfhost.d' },
    { title: 'landing.dev.sdk.t', status: 'planned', detail: 'landing.dev.sdk.d' },
  ];
  /** Code samples: identical in both languages (only the comment above the request is translated). */
  protected readonly requestHtml =
    '<span class="k">curl</span> -s -H <span class="s">"X-API-Key: $CHAINSENTINEL_API_KEY"</span> \\\n' +
    '  <span class="s">"https://&lt;your-host&gt;/api/v1/wallets/&lt;address&gt;/risk?network=tron"</span>';
  protected readonly responseHtml = [
    '{',
    '  <span class="k">"network"</span>: <span class="s">"tron"</span>,',
    '  <span class="k">"score"</span>: <span class="n">78</span>,',
    '  <span class="k">"level"</span>: <span class="s">"HIGH"</span>,',
    '  <span class="k">"contributions"</span>: [',
    '    { <span class="k">"title"</span>: <span class="s">"Fast forwarding"</span>, <span class="k">"points"</span>: <span class="n">29.0</span>,',
    '      <span class="k">"evidenceLevel"</span>: <span class="s">"RISK_SIGNAL"</span>, <span class="k">"ageSeconds"</span>: <span class="n">7200</span> },',
    '    { <span class="k">"title"</span>: <span class="s">"Structuring"</span>, <span class="k">"points"</span>: <span class="n">18.0</span>,',
    '      <span class="k">"evidenceLevel"</span>: <span class="s">"ANOMALY"</span>, <span class="k">"ageSeconds"</span>: <span class="n">259200</span> }',
    '  ],',
    '  <span class="k">"computedAt"</span>: <span class="s">"2026-11-09T14:06:04Z"</span>',
    '}',
  ].join('\n');

  protected copy(): void {
    const req = 'curl -s -H "X-API-Key: $CHAINSENTINEL_API_KEY" \\\n  "https://<your-host>/api/v1/wallets/<address>/risk?network=tron"';
    navigator.clipboard?.writeText(req).then(() => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 1800);
    }, () => undefined);
  }
}
