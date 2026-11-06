import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { SettingsService } from './core/settings.service';
import { environment } from '../environments/environment';
import { ThemeService } from './core/theme.service';
import { GlobalSearch } from './layout/global-search';

interface NavItem { path: string; label: string; icon: string; exact?: boolean }

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, GlobalSearch],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './app.scss',
  template: `
    <a class="skip" href="#main">Skip to content</a>
    <div class="shell" [class.nav-open]="navOpen()">
      <aside class="sidebar" aria-label="Primary">
        <div class="brand"><span class="logo" aria-hidden="true">◆</span><span>OpenChain<br /><small>Intelligence</small></span></div>
        <nav>
          @for (g of nav; track g.title) {
            <div class="nav-group">{{ g.title }}</div>
            @for (i of g.items; track i.path) {
              <a [routerLink]="i.path" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: !!i.exact }" ariaCurrentWhenActive="page" (click)="navOpen.set(false)">
                <span class="ico" aria-hidden="true">{{ i.icon }}</span>{{ i.label }}
              </a>
            }
          }
        </nav>
        <p class="disclaimer">Signals describe observed patterns. A signal is not proof of wrongdoing.</p>
      </aside>
      @if (navOpen()) { <div class="scrim" (click)="navOpen.set(false)" aria-hidden="true"></div> }
      <div class="main">
        <header class="topbar">
          <button class="btn btn--ghost menu" type="button" aria-label="Toggle navigation" [attr.aria-expanded]="navOpen()" (click)="navOpen.set(!navOpen())">☰</button>
          <oci-global-search class="search" />
          @if (allowMock && settings.connection().mock) {
            <a class="mock" routerLink="/settings" title="All data is fictitious. Disable mock mode in Settings to use the backend.">Mock data</a>
          }
          <button class="btn btn--ghost" type="button" (click)="theme.toggle()" [attr.aria-label]="'Switch to ' + (theme.effective() === 'dark' ? 'light' : 'dark') + ' theme'">
            {{ theme.effective() === 'dark' ? '☀' : '☾' }}
          </button>
        </header>
        <main id="main" tabindex="-1"><router-outlet /></main>
      </div>
    </div>
  `,
})
export class App {
  protected readonly theme = inject(ThemeService);
  protected readonly settings = inject(SettingsService);
  /** False in production builds: fake data can never be served there. */
  protected readonly allowMock = environment.allowMock;
  protected readonly navOpen = signal(false);
  protected readonly nav: { title: string; items: NavItem[] }[] = [
    { title: 'Monitor', items: [
      { path: '/', label: 'Overview', icon: '▦', exact: true },
      { path: '/alerts', label: 'Alert Center', icon: '⚑' },
    ] },
    { title: 'Investigate', items: [
      { path: '/explorer', label: 'Explorer', icon: '⌕' },
      { path: '/risk', label: 'Risk Intelligence', icon: '◔' },
      { path: '/investigation', label: 'Investigation', icon: '⋔' },
      { path: '/community', label: 'Community Intel', icon: '⚇' },
    ] },
    { title: 'Detect', items: [
      { path: '/rules/builder', label: 'Alert Builder', icon: '⧉' },
      { path: '/rules/code', label: 'Alert as Code', icon: '{}' },
      { path: '/rules/simulator', label: 'Rule Simulator', icon: '▷' },
    ] },
    { title: 'Configure', items: [
      { path: '/integrations', label: 'Integrations', icon: '⇄' },
      { path: '/settings', label: 'Settings', icon: '⚙' },
    ] },
  ];

  constructor() {
    inject(Router).events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => this.navOpen.set(false));
  }
}
