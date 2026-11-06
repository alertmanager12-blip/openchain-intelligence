import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { SettingsService } from './core/settings.service';
import { environment } from '../environments/environment';
import { ThemeService } from './core/theme.service';
import { CommandPalette } from './layout/command-palette';
import { NAV } from './layout/navigation';
import { ToastOutlet } from './layout/toast-outlet';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, CommandPalette, ToastOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './app.scss',
  host: { '(document:keydown)': 'globalKey($event)' },
  template: `
    <a class="skip" href="#main">Skip to content</a>
    <div class="shell" [class.nav-open]="navOpen()">
      <aside class="rail" id="rail" aria-label="Primary">
        <a class="brand" routerLink="/" aria-label="OpenChain Intelligence, overview">
          <svg class="mark" viewBox="0 0 40 8" aria-hidden="true"><rect x="0" y="0" width="14" height="8" rx="1" class="m-low"/><rect x="14.5" y="0" width="11.5" height="8" class="m-medium"/><rect x="26.5" y="0" width="7.5" height="8" class="m-high"/><rect x="34.5" y="0" width="5.5" height="8" rx="1" class="m-critical"/></svg>
          <span>OpenChain<span class="brand-2">Intelligence</span></span>
        </a>
        <nav>
          @for (g of nav; track g.title) {
            <div class="nav-group" role="group" [attr.aria-label]="g.title || 'Home'">
              @if (g.title) { <div class="nav-title" aria-hidden="true">{{ g.title }}</div> }
              @for (i of g.items; track i.path) {
                <a [routerLink]="i.path" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: !!i.exact }" ariaCurrentWhenActive="page" (click)="navOpen.set(false)">
                  <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"><path [attr.d]="i.icon"/></svg>{{ i.label }}</a>
              }
            </div>
          }
        </nav>
        <p class="disclaimer">Signals describe observed patterns, not proof of wrongdoing.</p>
      </aside>
      @if (navOpen()) { <div class="scrim" (click)="navOpen.set(false)" aria-hidden="true"></div> }
      <div class="main">
        <header class="topbar">
          <button class="btn btn--ghost btn--icon menu" type="button" aria-label="Show navigation" aria-controls="rail" [attr.aria-expanded]="navOpen()" (click)="navOpen.set(!navOpen())">
            <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 4h12M2 8h12M2 12h12"/></svg>
          </button>
          <button class="search" type="button" (click)="palette.set(true)" aria-haspopup="dialog" aria-keyshortcuts="Control+K Meta+K">
            <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="7" cy="7" r="4.5"/><path d="M10.5 10.5 14 14"/></svg>
            <span class="ph">Search an address, a transaction or a page</span>
            <kbd>{{ mac ? '⌘' : 'Ctrl' }} K</kbd>
          </button>
          <span class="spacer"></span>
          @if (allowMock && settings.connection().mock) {
            <a class="mock" routerLink="/settings" title="All data is fictitious. Turn off mock mode in Settings to use the backend.">Mock data</a>
          }
          <button class="btn btn--ghost theme" type="button" (click)="theme.toggle()" [attr.aria-label]="'Switch to ' + (theme.effective() === 'dark' ? 'light' : 'dark') + ' theme'">
            @if (theme.effective() === 'dark') {
              <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.4"><circle cx="8" cy="8" r="3"/><path d="M8 1.5v1.5M8 13v1.5M1.5 8H3M13 8h1.5M3.4 3.4l1 1M11.6 11.6l1 1M3.4 12.6l1-1M11.6 4.4l1-1"/></svg>
            } @else {
              <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.4"><path d="M13.5 9.5A5.5 5.5 0 0 1 6.5 2.5a5.5 5.5 0 1 0 7 7z"/></svg>
            }
            <span class="theme-text">{{ theme.effective() === 'dark' ? 'Light' : 'Dark' }}</span>
          </button>
        </header>
        <main id="main" tabindex="-1"><router-outlet /></main>
      </div>
    </div>
    <oci-command-palette [(open)]="palette" />
    <oci-toast-outlet />
  `,
})
export class App {
  protected readonly theme = inject(ThemeService);
  protected readonly settings = inject(SettingsService);
  /** False in production builds: fake data can never be served there. */
  protected readonly allowMock = environment.allowMock;
  protected readonly navOpen = signal(false);
  protected readonly palette = signal(false);
  protected readonly nav = NAV;
  protected readonly mac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

  constructor() {
    inject(Router).events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => this.navOpen.set(false));
  }

  protected globalKey(e: KeyboardEvent): void {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      this.palette.update((o) => !o);
    }
  }
}
