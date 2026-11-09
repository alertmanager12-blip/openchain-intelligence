import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
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
    .c { color: var(--cs-faint); } .k { color: var(--cs-flow); } .s { color: var(--cs-accent); } .n { color: var(--cs-warn); }
    @media (max-width: 960px) { .dev-grid { grid-template-columns: 1fr; gap: 40px; } .code { position: static; } }
  `,
  template: `
    <section class="cs-section" id="developers" aria-labelledby="dev-title">
      <div class="cs-wrap dev-grid">
        <div>
          <h2 class="cs-h2" id="dev-title">Intelligence that integrates into your ecosystem.</h2>
          <p class="cs-lede">Everything the console shows comes from the same REST API, so your backend can ask the questions your analysts ask.</p>
          <ul>
            @for (f of features; track f.title) {
              <li><strong>{{ f.title }}</strong><span class="cs-status cs-status--{{ f.status }}">{{ label[f.status] }}</span><p [innerHTML]="f.detail"></p></li>
            }
          </ul>
          @if (links.repository; as r) {
            <div class="links">
              <a class="cs-btn" [href]="links.docs" rel="noopener" target="_blank">API Documentation</a>
              <a class="cs-btn" [href]="links.contributing" rel="noopener" target="_blank">Developer Guide</a>
              <a class="cs-btn" [href]="r" rel="noopener" target="_blank">GitHub Repository</a>
            </div>
          } @else {
            <p class="nolinks">The documentation and the source will be linked here once the public repository is published.</p>
          }
        </div>

        <figure class="code" style="margin: 0" aria-labelledby="code-cap">
          <div class="code-head"><span id="code-cap">Score a wallet</span><span class="sp"></span><span class="cs-illus">Illustrative response</span>
            <button class="copy" type="button" (click)="copy()">{{ copied() ? 'Copied' : 'Copy request' }}</button></div>
          <pre><span class="c"># the address and the key are yours</span>
<span class="k">curl</span> -s -H <span class="s">"X-API-Key: $CHAINSENTINEL_API_KEY"</span> \\
  <span class="s">"https://&lt;your-host&gt;/api/v1/wallets/&lt;address&gt;/risk?network=tron"</span></pre>
          <pre>{{ '{' }}
  <span class="k">"network"</span>: <span class="s">"tron"</span>,
  <span class="k">"score"</span>: <span class="n">78</span>,
  <span class="k">"level"</span>: <span class="s">"HIGH"</span>,
  <span class="k">"contributions"</span>: [
    {{ '{' }} <span class="k">"title"</span>: <span class="s">"Fast forwarding"</span>, <span class="k">"points"</span>: <span class="n">29.0</span>,
      <span class="k">"evidenceLevel"</span>: <span class="s">"RISK_SIGNAL"</span>, <span class="k">"ageSeconds"</span>: <span class="n">7200</span> {{ '}' }},
    {{ '{' }} <span class="k">"title"</span>: <span class="s">"Structuring"</span>, <span class="k">"points"</span>: <span class="n">18.0</span>,
      <span class="k">"evidenceLevel"</span>: <span class="s">"ANOMALY"</span>, <span class="k">"ageSeconds"</span>: <span class="n">259200</span> {{ '}' }}
  ],
  <span class="k">"computedAt"</span>: <span class="s">"2026-11-09T14:06:04Z"</span>
{{ '}' }}</pre>
        </figure>
      </div>
    </section>
  `,
})
export class DevelopersSection {
  protected readonly links = publicLinks();
  protected readonly copied = signal(false);
  protected readonly label: Record<Availability, string> = { available: 'Available', partial: 'Partial', planned: 'Planned' };
  protected readonly features: readonly { title: string; detail: string; status: Availability }[] = [
    { title: 'REST API', status: 'available', detail: 'Versioned under <code>/api/v1</code>. Scoped API keys (<code>X-API-Key</code>) or OIDC bearer tokens.' },
    { title: 'OpenAPI', status: 'available', detail: 'Generated document and Swagger UI served by the backend when <code>OCI_API_DOCS_ENABLED=true</code>.' },
    { title: 'Webhooks', status: 'available', detail: 'Signed with HMAC-SHA256 and a timestamp (<code>X-OCI-Signature</code>), delivered with retries and a delivery id.' },
    { title: 'Detection rules as code', status: 'available', detail: 'YAML rules you can version, validate and replay on past events through the API.' },
    { title: 'Self-hosted deployment', status: 'available', detail: 'Docker Compose: backend, console and PostgreSQL. Prometheus metrics for indexing lag and processing time.' },
    { title: 'SDKs and MCP server', status: 'planned', detail: 'Client libraries and an MCP server for AI agents are on the roadmap. Use the REST API in the meantime.' },
  ];

  protected copy(): void {
    const req = 'curl -s -H "X-API-Key: $CHAINSENTINEL_API_KEY" \\\n  "https://<your-host>/api/v1/wallets/<address>/risk?network=tron"';
    navigator.clipboard?.writeText(req).then(() => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 1800);
    }, () => undefined);
  }
}
