import { ChangeDetectionStrategy, Component, ViewEncapsulation, inject, signal } from '@angular/core';
import { Meta } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { CapabilitiesSection } from './capabilities';
import { ClosingSections } from './closing';
import { DevelopersSection } from './developers';
import { HeroSection } from './hero';
import { HowItWorksSection } from './how-it-works';
import { InvestigationPreview } from './investigation-preview';
import { CsMark } from './mark';
import { ProblemSection } from './problem';
import { publicLinks } from './public-links';

export const LANDING_DESCRIPTION =
  'ChainSentinel is an open source, self-hosted blockchain intelligence platform: it indexes TRON and Ethereum, profiles wallets, ' +
  'explains risk signals, traces fund flows and turns your rules into alerts.';

/**
 * Public landing page ('/'), outside the console shell. Dark by design whatever the console theme. The page tokens and
 * the shared primitives (.cs-*) are declared here without encapsulation, scoped under `.cs`; each section keeps its own
 * encapsulated styles and reads the tokens through CSS custom properties.
 */
@Component({
  selector: 'oci-landing',
  imports: [RouterLink, CsMark, HeroSection, ProblemSection, CapabilitiesSection, HowItWorksSection, InvestigationPreview, DevelopersSection, ClosingSections],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  host: { class: 'cs', '(keydown.escape)': 'menuOpen.set(false)' },
  styles: `
    .cs {
      --cs-bg: #070a0f; --cs-bg-2: #0a0e15; --cs-surface: #0e141c; --cs-raised: #141b25; --cs-line: #1d2631; --cs-line-2: #2a3542;
      --cs-text: #e8edf3; --cs-muted: #9aa6b5; --cs-faint: #6f7c8b; --cs-accent: #3ddc97; --cs-accent-2: #2bb47b; --cs-accent-ink: #03150d;
      --cs-flow: #6fb7d9; --cs-warn: #f2b544; --cs-risk: #f0645c;
      --cs-font: var(--font); --cs-mono: var(--mono); --cs-wrap: 1180px; --cs-gutter: 24px; --cs-r: 10px; --cs-r-sm: 6px;
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
      border-radius: 8px; border: 1px solid var(--cs-line-2); background: transparent; color: var(--cs-text);
      font: 500 15px/1 var(--cs-font); text-decoration: none; cursor: pointer; white-space: nowrap;
      transition: background-color 160ms ease, border-color 160ms ease, color 160ms ease;
    }
    .cs-btn:hover { background: var(--cs-raised); border-color: #3a4757; }
    .cs-btn--primary { background: var(--cs-accent); border-color: var(--cs-accent); color: var(--cs-accent-ink); font-weight: 600; }
    .cs-btn--primary:hover { background: #5be6aa; border-color: #5be6aa; }
    .cs-btn--sm { height: 36px; padding: 0 14px; font-size: 14px; }
    .cs-link { color: var(--cs-muted); text-decoration: underline; text-decoration-color: var(--cs-line-2); text-underline-offset: 4px; }
    .cs-link:hover { color: var(--cs-text); text-decoration-color: var(--cs-accent); }
    .cs-illus {
      display: inline-flex; align-items: center; gap: 6px; padding: 2px 8px; border: 1px dashed var(--cs-line-2); border-radius: 999px;
      color: var(--cs-muted); font: 500 12px/1.6 var(--cs-font); white-space: nowrap;
    }
    .cs-status { display: inline-block; padding: 1px 8px; border-radius: 999px; font: 500 12px/1.6 var(--cs-font); white-space: nowrap; border: 1px solid; }
    .cs-status--available { color: var(--cs-accent); border-color: rgba(61, 220, 151, 0.35); background: rgba(61, 220, 151, 0.08); }
    .cs-status--partial { color: var(--cs-warn); border-color: rgba(242, 181, 68, 0.35); background: rgba(242, 181, 68, 0.08); }
    .cs-status--planned { color: var(--cs-muted); border-color: var(--cs-line-2); background: transparent; }

    /* header */
    .cs-top { position: sticky; top: 0; z-index: 20; background: rgba(7, 10, 15, 0.82); backdrop-filter: blur(12px) saturate(140%); border-bottom: 1px solid var(--cs-line); }
    .cs-top .cs-wrap { display: flex; align-items: center; gap: 24px; height: 64px; }
    .cs-brand { display: inline-flex; align-items: center; gap: 10px; font-weight: 600; font-size: 16px; letter-spacing: -0.015em; text-decoration: none; }
    .cs-nav { display: flex; gap: 4px; margin: 0 auto; }
    .cs-nav a { padding: 8px 12px; border-radius: 6px; color: var(--cs-muted); font-size: 14px; text-decoration: none; }
    .cs-nav a:hover { color: var(--cs-text); background: var(--cs-surface); }
    .cs-actions { display: flex; align-items: center; gap: 8px; }
    .cs-actions .cs-signin { color: var(--cs-muted); font-size: 14px; padding: 8px 10px; text-decoration: none; }
    .cs-actions .cs-signin:hover { color: var(--cs-text); }
    .cs-burger { display: none; width: 40px; height: 40px; margin-left: auto; border: 1px solid var(--cs-line-2); border-radius: 8px; background: none; color: var(--cs-text); cursor: pointer; }
    .cs-burger svg { width: 18px; height: 18px; }
    .cs-sheet { display: none; }
    @media (max-width: 900px) {
      .cs-nav, .cs-actions { display: none; }
      .cs-burger { display: inline-grid; place-items: center; }
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
        <a class="cs-brand" href="#top" aria-label="ChainSentinel, back to top"><oci-cs-mark [size]="26" />ChainSentinel</a>
        <nav class="cs-nav" aria-label="Primary">
          @for (l of sections; track l.href) { <a [href]="l.href">{{ l.label }}</a> }
          @if (links.docs; as d) { <a [href]="d" rel="noopener" target="_blank">Documentation</a> }
        </nav>
        <div class="cs-actions">
          @if (links.repository; as r) { <a class="cs-signin" [href]="r" rel="noopener" target="_blank">GitHub</a> }
          <a class="cs-signin" routerLink="/settings" [queryParams]="{ section: 'connection' }">Sign in</a>
          <a class="cs-btn cs-btn--primary cs-btn--sm" routerLink="/overview">Get started</a>
        </div>
        <button class="cs-burger" type="button" (click)="menuOpen.update((o) => !o)" [attr.aria-expanded]="menuOpen()" aria-controls="cs-sheet"
          [attr.aria-label]="menuOpen() ? 'Close menu' : 'Open menu'">
          <svg viewBox="0 0 18 18" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" aria-hidden="true">
            @if (menuOpen()) { <path d="m4 4 10 10M14 4 4 14"/> } @else { <path d="M3 5.5h12M3 9h12M3 12.5h12"/> }
          </svg>
        </button>
      </div>
      <div class="cs-sheet" id="cs-sheet" [hidden]="!menuOpen()">
        <nav aria-label="Mobile">
          @for (l of sections; track l.href) { <a [href]="l.href" (click)="menuOpen.set(false)">{{ l.label }}</a> }
          @if (links.docs; as d) { <a [href]="d" rel="noopener" target="_blank">Documentation</a> }
          @if (links.repository; as r) { <a [href]="r" rel="noopener" target="_blank">GitHub</a> }
          <a routerLink="/settings" [queryParams]="{ section: 'connection' }">Sign in</a>
        </nav>
        <div class="cs-sheet-cta"><a class="cs-btn cs-btn--primary" routerLink="/overview">Get started</a></div>
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
            <p>The open intelligence engine for blockchain. Self-hosted, explainable, Apache-2.0.</p>
          </div>
          <nav aria-labelledby="f-platform"><h2 id="f-platform">Platform</h2>
            <ul>@for (l of sections; track l.href) { <li><a [href]="l.href">{{ l.label }}</a></li> }</ul>
          </nav>
          <nav aria-labelledby="f-console"><h2 id="f-console">Console</h2>
            <ul>
              <li><a routerLink="/overview">Overview</a></li>
              <li><a routerLink="/alerts">Alerts</a></li>
              <li><a routerLink="/investigation">Fund flow graph</a></li>
              <li><a routerLink="/rules/builder">Rule builder</a></li>
              <li><a routerLink="/settings" [queryParams]="{ section: 'keys' }">API keys</a></li>
            </ul>
          </nav>
          <nav aria-labelledby="f-community"><h2 id="f-community">Community</h2>
            <ul>
              @if (links.repository; as r) {
                <li><a [href]="r" rel="noopener" target="_blank">GitHub</a></li>
                <li><a [href]="links.docs" rel="noopener" target="_blank">Documentation</a></li>
                <li><a [href]="links.contributing" rel="noopener" target="_blank">Contribution guide</a></li>
                <li><a [href]="links.issues" rel="noopener" target="_blank">Issues</a></li>
              } @else {
                <li><a href="#community">Contributing</a></li>
              }
              <li><a href="#open-source">License: Apache-2.0</a></li>
            </ul>
          </nav>
        </div>
        <div class="cs-foot-base">
          <span>© {{ year }} ChainSentinel contributors</span>
          <span>Signals are evidence for review, not proof of wrongdoing.</span>
        </div>
      </div>
    </footer>
  `,
})
export class LandingPage {
  protected readonly links = publicLinks();
  protected readonly menuOpen = signal(false);
  protected readonly year = new Date().getFullYear();
  protected readonly sections = [
    { href: '#platform', label: 'Platform' },
    { href: '#capabilities', label: 'Features' },
    { href: '#how-it-works', label: 'How it works' },
    { href: '#community', label: 'Community' },
  ];

  constructor() {
    const meta = inject(Meta);
    meta.updateTag({ name: 'description', content: LANDING_DESCRIPTION });
    meta.updateTag({ property: 'og:description', content: LANDING_DESCRIPTION });
  }
}
