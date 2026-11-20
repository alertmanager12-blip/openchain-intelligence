import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { I18nService } from '../../core/i18n/i18n.service';
import { RouterLink } from '@angular/router';
import { HeroTrace } from './hero-trace';
import { publicLinks } from './public-links';

/** Hero, followed by the coverage strip ("Platform"): only the networks and technologies that actually work. */
@Component({
  selector: 'oci-cs-hero',
  imports: [RouterLink, HeroTrace],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: block; position: relative; overflow: hidden; }
    :host::before {
      content: ''; position: absolute; inset: -40% -10% auto -10%; height: 900px; pointer-events: none;
      background: radial-gradient(ellipse 50% 40% at 70% 40%, rgba(61, 220, 151, 0.07), transparent 70%);
    }
    .hero { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.08fr); gap: 56px; align-items: center; padding-top: 88px; padding-bottom: 96px; }
    .pill { display: inline-flex; align-items: center; gap: 8px; padding: 4px 12px 4px 10px; border: 1px solid var(--cs-line-2); border-radius: 999px; color: var(--cs-muted); font-size: 13px; }
    .pill i { width: 7px; height: 7px; border-radius: 50%; background: var(--cs-accent); box-shadow: 0 0 0 3px rgba(61, 220, 151, 0.16); }
    h1 { margin-top: 24px; font-size: clamp(40px, 5.6vw, 68px); line-height: 1.02; font-weight: 650; letter-spacing: -0.04em; text-wrap: balance; }
    .sub { margin-top: 22px; font-size: 19px; line-height: 1.6; color: var(--cs-muted); max-width: 52ch; }
    .cta { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 36px; }
    .cta .cs-link { margin-left: 8px; font-size: 15px; }
    .facts { margin-top: 28px; color: var(--cs-faint); font-size: 14px; }
    .facts ::ng-deep b { color: var(--cs-muted); font-weight: 500; white-space: nowrap; }
    @media (max-width: 1000px) { .hero { grid-template-columns: minmax(0, 1fr); gap: 48px; padding-top: 56px; padding-bottom: 72px; } }

    .cover { position: relative; border-top: 1px solid var(--cs-line); padding: 40px 0 48px; background: var(--cs-bg-2); }
    .cover-grid { display: grid; grid-template-columns: minmax(180px, 0.8fr) minmax(0, 2.2fr); gap: 40px; align-items: start; }
    .cover h2 { font-size: 15px; font-weight: 600; letter-spacing: -0.01em; }
    .cover h2 + p { margin-top: 6px; color: var(--cs-muted); font-size: 14px; line-height: 1.5; }
    .nets { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
    .net { display: flex; gap: 14px; align-items: flex-start; padding: 16px; border: 1px solid var(--cs-line); border-radius: var(--cs-r); background: var(--cs-surface); }
    .tick { flex: none; display: grid; place-items: center; width: 40px; height: 32px; border-radius: 7px; background: var(--cs-raised); border: 1px solid var(--cs-line-2); font: 600 11px var(--cs-mono); color: var(--cs-muted); }
    .net strong { display: block; font-weight: 600; font-size: 15px; }
    .net span { color: var(--cs-muted); font-size: 14px; line-height: 1.5; }
    .more { grid-column: 1 / -1; color: var(--cs-muted); font-size: 14px; line-height: 1.55; }
    .tech { grid-column: 1 / -1; display: flex; flex-wrap: wrap; gap: 8px; margin-top: 4px; }
    .tech span { padding: 4px 10px; border: 1px solid var(--cs-line); border-radius: 6px; color: var(--cs-muted); font-size: 13px; }
    @media (max-width: 760px) { .cover-grid, .nets { grid-template-columns: 1fr; gap: 20px; } }
  `,
  template: `
    <section class="cs-wrap hero" aria-labelledby="hero-title">
      <div>
        <span class="pill"><i aria-hidden="true"></i>{{ i18n.t('landing.hero.pill') }}</span>
        <h1 id="hero-title">{{ i18n.t('landing.hero.title') }}</h1>
        <p class="sub">{{ i18n.t('landing.hero.sub') }}</p>
        <div class="cta">
          <a class="cs-btn cs-btn--primary" routerLink="/overview">{{ i18n.t('landing.hero.explore') }}</a>
          @if (links.repository; as r) { <a class="cs-btn" [href]="r" rel="noopener" target="_blank">{{ i18n.t('landing.hero.github') }}</a> }
          @if (links.docs; as d) { <a class="cs-link" [href]="d" rel="noopener" target="_blank">{{ i18n.t('landing.hero.docs') }}</a> }
          @else { <a class="cs-link" href="#how-it-works">{{ i18n.t('landing.hero.seeHow') }}</a> }
        </div>
        <p class="facts" [innerHTML]="i18n.t('landing.hero.facts')"></p>
      </div>
      <oci-cs-hero-trace />
    </section>

    <section class="cover" id="platform" aria-labelledby="cover-title">
      <div class="cs-wrap cover-grid">
        <div>
          <h2 id="cover-title">{{ i18n.t('landing.cover.title') }}</h2>
          <p>{{ i18n.t('landing.cover.lede') }}</p>
        </div>
        <div class="nets">
          <div class="net">
            <span class="tick" aria-hidden="true">ETH</span>
            <div><strong>Ethereum</strong><span>{{ i18n.t('landing.cover.eth') }}</span></div>
          </div>
          <div class="net">
            <span class="tick" aria-hidden="true">TRX</span>
            <div><strong>TRON</strong><span>{{ i18n.t('landing.cover.tron') }}</span></div>
          </div>
          <p class="more">{{ i18n.t('landing.cover.more') }}</p>
          <div class="tech" [attr.aria-label]="i18n.t('landing.cover.techAria')">
            @for (k of tech; track k) { <span>{{ i18n.t(k) }}</span> }
          </div>
        </div>
      </div>
    </section>
  `,
})
export class HeroSection {
  protected readonly i18n = inject(I18nService);
  protected readonly tech = ['landing.cover.tech1', 'landing.cover.tech2', 'landing.cover.tech3', 'landing.cover.tech4', 'landing.cover.tech5', 'landing.cover.tech6'] as const;
  protected readonly links = publicLinks();
}
