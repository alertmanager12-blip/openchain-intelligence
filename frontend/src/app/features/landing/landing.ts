import { ChangeDetectionStrategy, Component, ViewEncapsulation, effect, inject, signal } from '@angular/core';
import { Meta } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { I18nService, Key } from '../../core/i18n/i18n.service';
import { LangSwitch } from '../../layout/lang-switch';
import { CapabilitiesSection } from './capabilities';
import { ClosingSections } from './closing';
import { DevelopersSection } from './developers';
import { HeroSection } from './hero';
import { HowItWorksSection } from './how-it-works';
import { InvestigationPreview } from './investigation-preview';
import { CsMark } from './mark';
import { ProblemSection } from './problem';
import { publicLinks } from './public-links';

/**
 * Public landing page ('/'), outside the console shell. Dark by design whatever the console theme. The page tokens and
 * the shared primitives (.cs-*) are declared here without encapsulation, scoped under `.cs`; each section keeps its own
 * encapsulated styles and reads the tokens through CSS custom properties.
 */
@Component({
  selector: 'oci-landing',
  imports: [RouterLink, LangSwitch, CsMark, HeroSection, ProblemSection, CapabilitiesSection, HowItWorksSection, InvestigationPreview, DevelopersSection, ClosingSections],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  host: { class: 'cs', '(keydown.escape)': 'menuOpen.set(false)' },
  styles: `
    .cs {
      --cs-bg: #090f1a; --cs-bg-2: #0b1220; --cs-surface: #0f1829; --cs-raised: #151f33; --cs-line: #1b2740; --cs-line-2: #2a3a56;
      --cs-text: #e8eef6; --cs-muted: #a7b6c9; --cs-faint: #73849c; --cs-accent: #46cfdb; --cs-accent-2: #2fb6c3; --cs-accent-ink: #04131a;
      --cs-flow: #95a3ff; --cs-warn: #e0b343; --cs-risk: #f0525b;
      --cs-font: var(--font); --cs-mono: var(--mono); --cs-wrap: 1180px; --cs-gutter: 24px; --cs-r: 12px; --cs-r-sm: 8px;
      display: block; min-height: 100vh; background: var(--cs-bg); color: var(--cs-text); color-scheme: dark;
      font: 400 16px/1.6 var(--cs-font); letter-spacing: -0.006em; -webkit-font-smoothing: antialiased;
    }
    @media (max-width: 640px) { .cs { --cs-gutter: 16px; } }
    .cs *:focus-visible { outline: 2px solid var(--cs-accent); outline-offset: 3px; border-radius: 4px; }
    .cs [id] { scroll-margin-top: 80px; }
    .cs a { color: inherit; }
    .cs h1, .cs h2, .cs h3 { color: var(--cs-text); font-family: var(--cs-font); margin: 0; }
    .cs p { margin: 0; }
    .cs-wrap { width: 100%; max-width: calc(var(--cs-wrap) + 2 * var(--cs-gutter)); margin: 0 auto; padding: 0 var(--cs-gutter); }
    .cs-section { padding: 112px 0; border-top: 1px solid var(--cs-line); }
    @media (max-width: 760px) { .cs-section { padding: 72px 0; } }
    .cs-h2 { font-size: clamp(28px, 3.6vw, 44px); line-height: 1.1; font-weight: 600; letter-spacing: -0.028em; max-width: 22ch; text-wrap: balance; }
    .cs .cs-lede { margin-top: 18px; font-size: 18px; line-height: 1.6; color: var(--cs-muted); max-width: 62ch; }
    .cs-mono, .cs code { font-family: var(--cs-mono); font-size: 0.86em; letter-spacing: 0; font-feature-settings: normal; }
    .cs code { color: var(--cs-text); }
    .cs-btn {
      display: inline-flex; align-items: center; justify-content: center; gap: 8px; height: 44px; padding: 0 20px;
      border-radius: 10px; border: 1px solid var(--cs-line-2); background: transparent; color: var(--cs-text);
      font: 500 15px/1 var(--cs-font); text-decoration: none; cursor: pointer; white-space: nowrap;
      transition: background-color 160ms ease, border-color 160ms ease, color 160ms ease;
    }
    .cs-btn:hover { background: var(--cs-raised); border-color: #34496a; }
    .cs-btn--primary { background: var(--cs-accent); border-color: var(--cs-accent); color: var(--cs-accent-ink); font-weight: 600; box-shadow: inset 0 1px 0 rgb(255 255 255 / 22%), 0 8px 20px -10px rgba(70, 207, 219, 0.6); }
    .cs-btn--primary:hover { background: #6ddbe4; border-color: #6ddbe4; }
    .cs-btn--sm { height: 36px; padding: 0 14px; font-size: 14px; }
    .cs-link { color: var(--cs-muted); text-decoration: underline; text-decoration-color: var(--cs-line-2); text-underline-offset: 4px; }
    .cs-link:hover { color: var(--cs-text); text-decoration-color: var(--cs-accent); }
    .cs-illus {
      display: inline-flex; align-items: center; gap: 6px; padding: 2px 8px; border: 1px dashed var(--cs-line-2); border-radius: 999px;
      color: var(--cs-muted); font: 500 12px/1.6 var(--cs-font); white-space: nowrap;
    }
    .cs-status { display: inline-block; padding: 1px 8px; border-radius: 999px; font: 500 12px/1.6 var(--cs-font); white-space: nowrap; border: 1px solid; }
    .cs-status--available { color: var(--cs-accent); border-color: rgba(70, 207, 219, 0.35); background: rgba(70, 207, 219, 0.08); }
    .cs-status--partial { color: var(--cs-warn); border-color: rgba(242, 181, 68, 0.35); background: rgba(242, 181, 68, 0.08); }
    .cs-status--planned { color: var(--cs-muted); border-color: var(--cs-line-2); background: transparent; }

    /* header */
    .cs-top { position: sticky; top: 0; z-index: 20; background: rgba(9, 15, 26, 0.78); backdrop-filter: blur(14px) saturate(160%); -webkit-backdrop-filter: blur(14px) saturate(160%); border-bottom: 1px solid var(--cs-line); }
    .cs-top .cs-wrap { display: flex; align-items: center; gap: 24px; height: 64px; }
    .cs-brand { display: inline-flex; align-items: center; gap: 10px; font-weight: 600; font-size: 16px; letter-spacing: -0.015em; text-decoration: none; }
    .cs-nav { display: flex; gap: 4px; margin: 0 auto; }
    .cs-nav a { padding: 8px 12px; border-radius: 8px; color: var(--cs-muted); font-size: 14px; text-decoration: none; }
    .cs-nav a:hover { color: var(--cs-text); background: var(--cs-surface); }
    .cs-actions { display: flex; align-items: center; gap: 8px; }
    .cs-actions .cs-signin { color: var(--cs-muted); font-size: 14px; padding: 8px 10px; text-decoration: none; }
    .cs-actions .cs-signin:hover { color: var(--cs-text); }
    .cs-burger { display: none; width: 40px; height: 40px; margin-left: auto; border: 1px solid var(--cs-line-2); border-radius: 10px; background: none; color: var(--cs-text); cursor: pointer; }
    .cs-burger svg { width: 18px; height: 18px; }
    .cs-sheet { display: none; }
    .cs oci-lang-switch { --line: var(--cs-line-2); --muted: var(--cs-muted); --accent: var(--cs-accent); --accent-tint: rgba(70, 207, 219, 0.12); --focus: var(--cs-accent); }
    @media (max-width: 900px) {
      .cs-nav, .cs-actions { display: none; }
      .cs-top oci-lang-switch { margin-left: auto; }
      .cs-burger { display: inline-grid; place-items: center; margin-left: 0; }
      .cs-sheet { display: block; border-top: 1px solid var(--cs-line); background: var(--cs-bg); padding: 8px var(--cs-gutter) 20px; }
      .cs-sheet[hidden] { display: none; }
      .cs-sheet a:not(.cs-btn) { display: block; padding: 12px 4px; color: var(--cs-text); text-decoration: none; border-bottom: 1px solid var(--cs-line); font-size: 16px; }
      .cs-sheet .cs-sheet-cta { display: grid; gap: 10px; margin-top: 16px; }
    }

    /* footer */
    .cs-foot { border-top: 1px solid var(--cs-line); padding: 64px 0 40px; color: var(--cs-muted); font-size: 14px; }
    .cs-foot-grid { display: grid; grid-template-columns: minmax(220px, 1.4fr) repeat(3, minmax(0, 1fr)); gap: 40px; }
    .cs-foot h2 { font-size: 14px; font-weight: 600; margin-bottom: 14px; }
    .cs-foot ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
    .cs-foot a { text-decoration: none; }
    .cs-foot a:hover { color: var(--cs-text); }
    .cs-foot-about p { margin-top: 14px; max-width: 34ch; line-height: 1.55; }
    .cs-foot-base { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 12px; margin-top: 56px; padding-top: 24px; border-top: 1px solid var(--cs-line); color: var(--cs-faint); }
    @media (max-width: 760px) { .cs-foot-grid { grid-template-columns: 1fr 1fr; } .cs-foot-about { grid-column: 1 / -1; } }
  `,
  template: `
    <header class="cs-top">
      <div class="cs-wrap">
        <a class="cs-brand" href="#top" [attr.aria-label]="i18n.t('landing.brandAria')"><oci-cs-mark [size]="26" />ChainSentinel</a>
        <nav class="cs-nav" [attr.aria-label]="i18n.t('landing.navPrimary')">
          @for (l of sections; track l.href) { <a [href]="l.href">{{ i18n.t(l.label) }}</a> }
          @if (links.docs; as d) { <a [href]="d" rel="noopener" target="_blank">{{ i18n.t('landing.nav.docs') }}</a> }
        </nav>
        <div class="cs-actions">
          @if (links.repository; as r) { <a class="cs-signin" [href]="r" rel="noopener" target="_blank">{{ i18n.t('landing.github') }}</a> }
          <a class="cs-signin" routerLink="/settings" [queryParams]="{ section: 'connection' }">{{ i18n.t('landing.signIn') }}</a>
          <a class="cs-btn cs-btn--primary cs-btn--sm" routerLink="/overview">{{ i18n.t('landing.getStarted') }}</a>
        </div>
        <oci-lang-switch />
        <button class="cs-burger" type="button" (click)="toggleMenu()" [attr.aria-expanded]="menuOpen()" aria-controls="cs-sheet"
          [attr.aria-label]="menuOpen() ? i18n.t('landing.menuClose') : i18n.t('landing.menuOpen')">
          <svg viewBox="0 0 18 18" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" aria-hidden="true">
            @if (menuOpen()) { <path d="m4 4 10 10M14 4 4 14"/> } @else { <path d="M3 5.5h12M3 9h12M3 12.5h12"/> }
          </svg>
        </button>
      </div>
      <div class="cs-sheet" id="cs-sheet" [hidden]="!menuOpen()">
        <nav [attr.aria-label]="i18n.t('landing.navMobile')">
          @for (l of sections; track l.href) { <a [href]="l.href" (click)="menuOpen.set(false)">{{ i18n.t(l.label) }}</a> }
          @if (links.docs; as d) { <a [href]="d" rel="noopener" target="_blank">{{ i18n.t('landing.nav.docs') }}</a> }
          @if (links.repository; as r) { <a [href]="r" rel="noopener" target="_blank">{{ i18n.t('landing.github') }}</a> }
          <a routerLink="/settings" [queryParams]="{ section: 'connection' }">{{ i18n.t('landing.signIn') }}</a>
        </nav>
        <div class="cs-sheet-cta"><a class="cs-btn cs-btn--primary" routerLink="/overview">{{ i18n.t('landing.getStarted') }}</a></div>
      </div>
    </header>

    <div id="top"></div>
    <oci-cs-hero />
    <oci-cs-problem />
    <oci-cs-capabilities />
    <oci-cs-how />
    <oci-cs-investigation />
    <oci-cs-developers />
    <oci-cs-closing />

    <footer class="cs-foot">
      <div class="cs-wrap">
        <div class="cs-foot-grid">
          <div class="cs-foot-about">
            <a class="cs-brand" href="#top"><oci-cs-mark [size]="24" />ChainSentinel</a>
            <p>{{ i18n.t('landing.foot.about') }}</p>
          </div>
          <nav aria-labelledby="f-platform"><h2 id="f-platform">{{ i18n.t('landing.foot.platform') }}</h2>
            <ul>@for (l of sections; track l.href) { <li><a [href]="l.href">{{ i18n.t(l.label) }}</a></li> }</ul>
          </nav>
          <nav aria-labelledby="f-console"><h2 id="f-console">{{ i18n.t('landing.foot.console') }}</h2>
            <ul>
              <li><a routerLink="/overview">{{ i18n.t('landing.foot.overview') }}</a></li>
              <li><a routerLink="/alerts">{{ i18n.t('landing.foot.alerts') }}</a></li>
              <li><a routerLink="/investigation">{{ i18n.t('landing.foot.graph') }}</a></li>
              <li><a routerLink="/rules/builder">{{ i18n.t('landing.foot.builder') }}</a></li>
              <li><a routerLink="/settings" [queryParams]="{ section: 'keys' }">{{ i18n.t('landing.foot.keys') }}</a></li>
            </ul>
          </nav>
          <nav aria-labelledby="f-community"><h2 id="f-community">{{ i18n.t('landing.foot.community') }}</h2>
            <ul>
              @if (links.repository; as r) {
                <li><a [href]="r" rel="noopener" target="_blank">{{ i18n.t('landing.github') }}</a></li>
                <li><a [href]="links.docs" rel="noopener" target="_blank">{{ i18n.t('landing.nav.docs') }}</a></li>
                <li><a [href]="links.contributing" rel="noopener" target="_blank">{{ i18n.t('landing.foot.contributing') }}</a></li>
                <li><a [href]="links.issues" rel="noopener" target="_blank">{{ i18n.t('landing.foot.issues') }}</a></li>
              } @else {
                <li><a href="#community">{{ i18n.t('landing.foot.contribute') }}</a></li>
              }
              <li><a href="#open-source">{{ i18n.t('landing.foot.license') }}</a></li>
            </ul>
          </nav>
        </div>
        <div class="cs-foot-base">
          <span>{{ i18n.t('landing.foot.copyright', { year }) }}</span>
          <span>{{ i18n.t('landing.foot.caveat') }}</span>
        </div>
      </div>
    </footer>
  `,
})
export class LandingPage {
  protected readonly i18n = inject(I18nService);
  protected readonly links = publicLinks();
  protected readonly menuOpen = signal(false);
  protected readonly year = new Date().getFullYear();
  protected readonly sections: readonly { href: string; label: Key }[] = [
    { href: '#platform', label: 'landing.nav.platform' },
    { href: '#capabilities', label: 'landing.nav.features' },
    { href: '#how-it-works', label: 'landing.nav.how' },
    { href: '#community', label: 'landing.nav.community' },
  ];

  protected toggleMenu(): void { this.menuOpen.update((o) => !o); }

  constructor() {
    const meta = inject(Meta);
    effect(() => {
      const description = this.i18n.t('landing.description');
      meta.updateTag({ name: 'description', content: description });
      meta.updateTag({ property: 'og:description', content: description });
    });
  }
}
