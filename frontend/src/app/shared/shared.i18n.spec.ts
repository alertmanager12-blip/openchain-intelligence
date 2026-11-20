import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { Component, Injector, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatButtonHarness } from '@angular/material/button/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatDialogHarness } from '@angular/material/dialog/testing';
import { provideRouter } from '@angular/router';
import { I18nService } from '../core/i18n/i18n.service';
import { Toasts } from '../core/toasts';
import { CommandPalette } from '../layout/command-palette';
import { SettingsService } from '../core/settings.service';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { authInterceptor, errorInterceptor } from '../core/interceptors';
import { mockBackendInterceptor } from '../core/mock/mock-backend.interceptor';
import { ShortcutHelp } from '../layout/shortcut-help';
import { ToastOutlet } from '../layout/toast-outlet';
import { confirmAction } from './confirm-dialog';
import { factorTitle } from './explain';
import { formatAge, timeAgo } from './format';
import { detectQuery } from '../core/network-detect';
import { translate } from '../core/i18n/i18n.service';
import { listOf } from './network-not-enabled';
import { RiskGauge } from './risk-gauge';
import { StateComponent } from './ui';

@Component({ template: '' })
class Empty {}

@Component({ imports: [ShortcutHelp], template: `<oci-shortcut-help [(open)]="open" />` })
class HelpHost { readonly open = signal(false); }

@Component({ imports: [ToastOutlet], template: `<oci-toast-outlet />` })
class ToastHost {}

@Component({ imports: [CommandPalette], template: `<oci-command-palette [(open)]="open" />` })
class PaletteHost { readonly open = signal(false); }

const settle = (ms = 450) => new Promise((r) => setTimeout(r, ms));

/** Spec 2026-11-19: shared components, dialogs and toasts follow the console language, live. */
describe('shared components in French and English', () => {
  afterEach(() => { TestBed.inject(I18nService).set('en'); localStorage.removeItem('oci.lang'); });

  it('confirm dialog: Annuler / Confirmer, and it re-renders in English without closing', async () => {
    localStorage.setItem('oci.lang', 'fr');
    const dialog = TestBed.inject(MatDialog);
    const pending = confirmAction(dialog, TestBed.inject(Injector), { title: { key: 'dialog.sample.title' }, message: { key: 'dialog.sample.message' } });
    const loader = TestbedHarnessEnvironment.documentRootLoader(TestBed.createComponent(Empty));
    const d = await loader.getHarness(MatDialogHarness);
    expect(await d.getTitleText()).toBe('Supprimer cet élément ?');
    expect(await (await d.getHarness(MatButtonHarness.with({ text: 'Annuler' }))).getText()).toBe('Annuler');
    expect(await (await d.getHarness(MatButtonHarness.with({ text: 'Confirmer' }))).getText()).toBe('Confirmer');
    TestBed.inject(I18nService).set('en');
    expect(await d.getTitleText()).toBe('Delete this item?');
    await (await d.getHarness(MatButtonHarness.with({ text: 'Cancel' }))).click();
    expect(await pending).toBe(false);
  });

  it('table empty state and loading label', async () => {
    localStorage.setItem('oci.lang', 'fr');
    const f = TestBed.createComponent(StateComponent);
    f.componentRef.setInput('empty', true);
    await f.whenStable();
    expect((f.nativeElement as HTMLElement).textContent).toContain('Rien à afficher pour le moment.');
    TestBed.inject(I18nService).set('en');
    await f.whenStable();
    expect((f.nativeElement as HTMLElement).textContent).toContain('Nothing to show yet.');
    f.componentRef.setInput('empty', false);
    f.componentRef.setInput('loading', true);
    await f.whenStable();
    expect((f.nativeElement as HTMLElement).querySelector('oci-skeleton')!.getAttribute('aria-label')).toBe('Loading');
    TestBed.inject(I18nService).set('fr');
    await f.whenStable();
    expect((f.nativeElement as HTMLElement).querySelector('oci-skeleton')!.getAttribute('aria-label')).toBe('Chargement');
  });

  it('shortcut help: "Alerte suivante", and it re-renders in English while open', async () => {
    localStorage.setItem('oci.lang', 'fr');
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const f = TestBed.createComponent(HelpHost);
    document.body.appendChild(f.nativeElement);
    f.componentInstance.open.set(true);
    await f.whenStable();
    await settle();
    const dlg = () => document.querySelector('mat-dialog-container')!;
    expect(dlg().textContent).toContain('Alerte suivante');
    expect(dlg().textContent).toContain('Général');
    expect(dlg().textContent).toContain('puis');
    TestBed.inject(I18nService).set('en');
    await f.whenStable();
    await settle(50);
    expect(dlg()).not.toBeNull();
    expect(dlg().textContent).toContain('Next alert');
    expect(dlg().textContent).toContain('General');
    f.destroy();
  });

  it('toast: dismiss button and list label in French', async () => {
    localStorage.setItem('oci.lang', 'fr');
    const f = TestBed.createComponent(ToastHost);
    await f.whenStable();
    TestBed.inject(Toasts).success('Alerte acquittée', 'Détail');
    await f.whenStable();
    await settle(100);
    await f.whenStable();
    const stack = document.querySelector('oci-toast-stack')!;
    expect(stack.textContent).toContain('Alerte acquittée');
    expect(stack.querySelector('button')!.getAttribute('aria-label')).toBe('Fermer la notification');
    TestBed.inject(I18nService).set('en');
    await f.whenStable();
    expect(stack.querySelector('button')!.getAttribute('aria-label')).toBe('Dismiss notification');
    f.destroy();
  });

  it('risk gauge speaks French', async () => {
    localStorage.setItem('oci.lang', 'fr');
    const f = TestBed.createComponent(RiskGauge);
    f.componentRef.setInput('score', 72);
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    expect(el.getAttribute('aria-label')).toBe('Score de risque');
    expect(el.getAttribute('aria-valuetext')).toBe('72 sur 100, risque élevé');
    f.componentRef.setInput('score', null);
    await f.whenStable();
    expect(el.getAttribute('aria-valuetext')).toBe('Non évalué');
  });

  it('lists names with the language\'s conjunction', () => {
    expect(listOf(['Ethereum', 'TRON', 'BSC'], 'fr')).toBe('Ethereum, TRON et BSC');
    expect(listOf(['Ethereum', 'TRON', 'BSC'], 'en')).toBe('Ethereum, TRON and BSC');
  });

  it('ages and factor names in both languages', () => {
    const now = Date.parse('2026-11-20T10:00:00Z');
    expect(timeAgo('2026-11-20T09:55:00Z', now)).toBe('5m ago');
    expect(timeAgo('2026-11-20T09:55:00Z', now, 'fr')).toBe('il y a 5 min');
    expect(formatAge(7200, 'fr')).toBe('2 h');
    expect(formatAge(172800, 'fr')).toBe('2 j');
    expect(factorTitle('label:SANCTIONED', 'fr')).toBe('Listée comme sanctionnée');
    expect(factorTitle('signal:RAPID_DRAIN', 'fr')).toBe('Vidage rapide');
  });

  it('explains the detected query format in French', () => {
    expect(translate('fr', detectQuery('0x' + 'a'.repeat(64)).reasonKey)).toBe('Hash de transaction EVM (0x + 64 caractères hexadécimaux)');
    expect(translate('en', detectQuery('').reasonKey)).toBe('Empty query');
  });

  it('command palette: French labels, live switch', async () => {
    localStorage.setItem('oci.lang', 'fr');
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor, errorInterceptor, mockBackendInterceptor]))] });
    TestBed.inject(SettingsService).update({ mock: true });
    const f = TestBed.createComponent(PaletteHost);
    document.body.appendChild(f.nativeElement);
    f.componentInstance.open.set(true);
    await f.whenStable();
    await settle();
    const input = document.querySelector<HTMLInputElement>('input[role="combobox"]')!;
    input.value = 'zzzzzz'; input.dispatchEvent(new Event('input'));
    await f.whenStable();
    const dlg = () => document.querySelector('mat-dialog-container')!;
    expect(dlg().textContent).toContain('Aucun résultat.');
    expect(dlg().textContent).toContain('naviguer');
    TestBed.inject(I18nService).set('en');
    await f.whenStable();
    expect(dlg().textContent).toContain('Nothing matches.');
    f.destroy();
  });
});
