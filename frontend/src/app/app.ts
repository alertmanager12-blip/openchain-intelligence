import { BreakpointObserver } from '@angular/cdk/layout';
import { ChangeDetectionStrategy, Component, ElementRef, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { ThemeService } from './core/theme.service';
import { CommandPalette } from './layout/command-palette';
import { NAV, NAV_ITEMS, NavItem } from './layout/navigation';
import { Shell, isTypingTarget } from './layout/shell';
import { ShortcutHelp } from './layout/shortcut-help';
import { ToastOutlet } from './layout/toast-outlet';

/** The sidebar is docked from this width; below it, it opens as a drawer over the page. */
export const RAIL_DOCKED = '(min-width: 1000px)';
/** Second key of a "G then key" sequence must follow within this delay. */
const SEQUENCE_MS = 1200;

type Menu = 'env' | 'user' | null;

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, CommandPalette, ShortcutHelp, ToastOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './app.scss',
  host: {
    '(document:keydown)': 'globalKey($event)',
    '(document:click)': 'outsideClick($event)',
    '[class.collapsed]': 'docked() && shell.collapsed()',
    '[class.mobile]': '!docked()',
  },
  template: `
    <a class="skip" href="#main">Skip to content</a>
    <aside class="side" id="sidebar" aria-label="Sidebar" [class.open]="!docked() && shell.drawerOpen()" [attr.inert]="!docked() && !shell.drawerOpen() ? '' : null"
      (keydown.escape)="closeDrawer()">
      <div class="side-top">
        <div class="pop-wrap">
          <button class="env" type="button" (click)="toggle('env', $event)" [attr.aria-expanded]="menu() === 'env'" aria-controls="env-pop"
            [attr.aria-label]="'OpenChain Intelligence, ' + shell.env().label + '. Environment details'">
            <svg class="mark" viewBox="0 0 20 20" aria-hidden="true"><rect x="1" y="1" width="18" height="18" rx="5" class="mark-bg"/><rect x="5" y="11" width="2.4" height="4" rx="1" class="m-bar"/><rect x="8.8" y="8.5" width="2.4" height="6.5" rx="1" class="m-bar" opacity="0.8"/><rect x="12.6" y="5" width="2.4" height="10" rx="1" class="m-bar"/></svg>
            <span class="env-text"><span class="product">OpenChain Intelligence</span><span class="env-line"><span class="env-dot k-{{ shell.env().kind }}" aria-hidden="true"></span>{{ shell.env().label }}</span></span>
          </button>
          @if (menu() === 'env') {
            <div class="pop pop--env" id="env-pop" role="region" aria-label="Environment">
              <div class="pop-head"><span class="env-dot k-{{ shell.env().kind }}" aria-hidden="true"></span><strong>{{ shell.env().label }}</strong></div>
              <p class="pop-text">{{ shell.env().detail }}</p>
              @if (shell.networkCount()) { <p class="pop-text">{{ shell.networkCount() }} networks configured@if (shell.simulatedNetworks()) {, {{ shell.simulatedNetworks() }} simulated}.</p> }
              <div class="pop-sep"></div>
              <a class="pop-item" routerLink="/settings" [queryParams]="{ section: 'connection' }" (click)="menu.set(null)">Connection settings</a>
              <a class="pop-item" routerLink="/" (click)="menu.set(null)">Network health on Overview</a>
            </div>
          }
        </div>
      </div>

      <nav class="nav" aria-label="Primary">
        @for (g of nav; track g.title) {
          <div class="nav-group" role="group" [attr.aria-label]="g.title || 'Home'">
            @if (g.title) { <div class="nav-title" aria-hidden="true">{{ g.title }}</div> }
            @for (i of g.items; track i.path) {
              <a class="nav-link" [routerLink]="i.path" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: !!i.exact }" ariaCurrentWhenActive="page"
                [attr.aria-keyshortcuts]="i.key ? 'G ' + i.key.toUpperCase() : null" [attr.title]="shell.collapsed() && docked() ? i.label : null">
                <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"><path [attr.d]="i.icon"/></svg>
                <span class="nav-label">{{ i.label }}</span>
                @if (count(i); as c) { <span class="nav-count" [attr.aria-label]="c + ' ' + countLabel(i)">{{ c }}</span> }
                @if (i.key) { <span class="nav-keys" aria-hidden="true"><kbd>G</kbd><kbd>{{ i.key.toUpperCase() }}</kbd></span> }
              </a>
            }
          </div>
        }
      </nav>

      <div class="side-bottom">
        <p class="disclaimer">Signals are not proof of wrongdoing.</p>
        <div class="user-row">
        <div class="pop-wrap">
          <button class="user" type="button" (click)="toggle('user', $event)" [attr.aria-expanded]="menu() === 'user'" aria-controls="user-pop"
            [attr.aria-label]="'Credential: ' + shell.credential().label + (shell.credential().prefix ? ' ' + shell.credential().prefix : '') + '. Account menu'">
            <span class="avatar" [class.on]="shell.credential().signedIn" aria-hidden="true">
              <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4"><circle cx="5.5" cy="10.5" r="2.5"/><path d="m7.3 8.7 5.2-5.2M10.5 5.5l1.5 1.5M12 4l1.5 1.5"/></svg>
            </span>
            <span class="user-text"><span class="user-mode">{{ shell.credential().label }}</span>
              <span class="user-key" [class.mono]="!!shell.credential().prefix">{{ shell.credential().prefix ?? 'Set one in Settings' }}</span></span>
            <svg class="chev" viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m5 6.5 3-3 3 3M5 9.5l3 3 3-3"/></svg>
          </button>
          @if (menu() === 'user') {
            <div class="pop pop--user" id="user-pop" role="region" aria-label="Account">
              <div class="pop-head"><strong>{{ shell.credential().label }}</strong></div>
              <p class="pop-text">@if (shell.credential().prefix; as p) { Using <span class="mono">{{ p }}</span>, stored in this browser only. } @else { No credential is stored in this browser. }</p>
              <div class="pop-sep"></div>
              <a class="pop-item" routerLink="/settings" [queryParams]="{ section: 'connection' }" (click)="menu.set(null)">Settings</a>
              <a class="pop-item" routerLink="/settings" [queryParams]="{ section: 'keys' }" (click)="menu.set(null)">API keys</a>
              <button class="pop-item" type="button" (click)="theme.toggle()">{{ theme.effective() === 'dark' ? 'Light theme' : 'Dark theme' }}</button>
              <button class="pop-item" type="button" (click)="menu.set(null); shell.help.set(true)">Keyboard shortcuts<kbd>?</kbd></button>
              <div class="pop-sep"></div>
              <button class="pop-item danger" type="button" (click)="signOut()" [disabled]="!shell.credential().prefix">Sign out</button>
            </div>
          }
        </div>
          @if (docked()) {
            <button class="icon-btn collapse" type="button" (click)="shell.toggleCollapsed()" [attr.aria-label]="shell.collapsed() ? 'Expand sidebar' : 'Collapse sidebar'"
              [attr.aria-pressed]="shell.collapsed()" aria-keyshortcuts="[" [attr.title]="(shell.collapsed() ? 'Expand' : 'Collapse') + ' sidebar  ['">
              <svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.4"><rect x="2" y="2.5" width="12" height="11" rx="2"/><path d="M6.5 2.5v11"/></svg>
            </button>
          }
        </div>
      </div>
    </aside>
    @if (!docked() && shell.drawerOpen()) { <div class="scrim" (click)="closeDrawer()" aria-hidden="true"></div> }

    <main id="main" tabindex="-1"><router-outlet /></main>

    <!-- The palette and the shortcut help (MatDialog) load on first use, prefetched when the browser is idle. -->
    @defer (when shell.palette(); prefetch on idle) { <oci-command-palette [(open)]="shell.palette" /> }
    @defer (when shell.help(); prefetch on idle) { <oci-shortcut-help [(open)]="shell.help" /> }
    <oci-toast-outlet />
  `,
})
export class App {
  protected readonly theme = inject(ThemeService);
  protected readonly shell = inject(Shell);
  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly docked = toSignal(inject(BreakpointObserver).observe(RAIL_DOCKED).pipe(map((s) => s.matches)), { initialValue: true });
  protected readonly nav = NAV;
  protected readonly menu = signal<Menu>(null);
  private pendingG = 0;
  private drawerOpener: HTMLElement | null = null;

  constructor() {
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => {
      this.shell.drawerOpen.set(false);
      this.menu.set(null);
    });
    // mobile drawer: focus moves into it on open and back to the opener on close
    effect(() => {
      const open = this.shell.drawerOpen() && !this.docked();
      untracked(() => {
        if (open) {
          this.drawerOpener = document.activeElement as HTMLElement | null;
          queueMicrotask(() => this.host.nativeElement.querySelector<HTMLElement>('.side .nav-link.active, .side .nav-link')?.focus());
        } else if (this.drawerOpener) {
          this.drawerOpener.focus?.();
          this.drawerOpener = null;
        }
      });
    });
  }

  protected count(i: NavItem): number | null {
    const v = i.count === 'openAlerts' ? this.shell.openAlerts() : i.count === 'rulesEnabled' ? this.shell.rulesEnabled() : null;
    return v ? v : null;
  }

  protected countLabel(i: NavItem): string {
    return i.count === 'openAlerts' ? 'open alerts' : 'enabled rules';
  }

  protected toggle(m: Exclude<Menu, null>, e: Event): void {
    e.stopPropagation();
    this.menu.update((v) => (v === m ? null : m));
  }

  protected closeDrawer(): void {
    this.shell.drawerOpen.set(false);
  }

  protected signOut(): void {
    this.menu.set(null);
    this.shell.signOut();
  }

  protected outsideClick(e: MouseEvent): void {
    if (this.menu() && !(e.target as HTMLElement | null)?.closest?.('.pop-wrap')) this.menu.set(null);
  }

  protected globalKey(e: KeyboardEvent): void {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      this.shell.palette.update((o) => !o);
      return;
    }
    if (e.key === 'Escape' && this.menu()) {
      const m = this.menu();
      this.menu.set(null);
      this.host.nativeElement.querySelector<HTMLElement>(m === 'env' ? '.env' : '.user')?.focus();
      return;
    }
    if (e.metaKey || e.ctrlKey || e.altKey || e.defaultPrevented || isTypingTarget(e.target) || document.querySelector('mat-dialog-container')) return;
    if (e.key === '?') {
      e.preventDefault();
      this.shell.help.set(true);
    } else if (e.key === '[' && this.docked()) {
      this.shell.toggleCollapsed();
    } else if (this.pendingG && Date.now() - this.pendingG < SEQUENCE_MS) {
      this.pendingG = 0;
      const item = NAV_ITEMS.find((i) => i.key === e.key.toLowerCase());
      if (item) {
        e.preventDefault();
        void this.router.navigateByUrl(item.path);
      }
    } else if (e.key === 'g' || e.key === 'G') {
      this.pendingG = Date.now();
    }
  }
}
