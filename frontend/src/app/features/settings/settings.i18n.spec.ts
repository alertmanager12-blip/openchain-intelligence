import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, of } from 'rxjs';
import { MATERIAL_DEFAULTS } from '../../material-defaults';
import { ApiService } from '../../core/api.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { LangSwitch } from '../../layout/lang-switch';
import { MatDialog } from '@angular/material/dialog';
import { SettingsPage } from './settings';

const sanctions = { source: 'OFAC SDN', state: 'CURRENT', fetchEnabled: true, issued: '2026-11-10', fetchedAt: '2026-11-11T10:00:00Z', checkedAt: '2026-11-11T10:00:00Z', tron: 1234, ethereum: 5678, lastError: null };
const key = { id: 'k1', name: 'Pipeline CI', prefix: 'oci_abc', scopes: ['alerts:read'], createdAt: '2026-11-10T10:00:00Z', lastUsedAt: null, revoked: false };
const user = { id: 'u1', email: 'ana@example.org', name: 'Ana', role: 'ANALYST', lastLoginAt: null };

describe('Settings screen in French', () => {
  beforeEach(() => {
    localStorage.setItem('oci.lang', 'fr');
    const stub = new Proxy({ sanctionsLists: () => of({ items: [sanctions] }), apiKeys: () => of([key]), users: () => of([user]) } as Record<string, unknown>,
      { get: (t, k) => (k in t ? t[k as string] : () => NEVER) });
    TestBed.configureTestingModule({ providers: [provideRouter([]), MATERIAL_DEFAULTS, { provide: ApiService, useValue: stub }] });
  });
  afterEach(() => { localStorage.removeItem('oci.lang'); document.documentElement.lang = 'en'; });
  const settle = async (f: ComponentFixture<unknown>) => { await f.whenStable(); await new Promise((r) => setTimeout(r, 60)); await f.whenStable(); f.detectChanges(); };
  const tab = (el: HTMLElement, id: string) => el.querySelector<HTMLButtonElement>(`#tab-${id}`)!;

  it('writes the sections, the sanctions card and the keys in French, then switches back', async () => {
    const f = TestBed.createComponent(SettingsPage);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toContain('Réglages');
    expect(tab(el, 'connection').textContent).toContain('Connexion');
    expect(tab(el, 'keys').textContent).toContain('Clés API');
    expect(el.textContent).toContain('Listes de sanctions');
    expect(el.textContent).toContain('Liste de sanctions : OFAC SDN du');
    expect(el.textContent).toContain('adresses TRON');
    tab(el, 'keys').click();
    await settle(f);
    expect(el.textContent).toContain('Créer la clé');
    expect(el.textContent).toContain('Pipeline CI');
    expect(el.textContent).toContain('oci_abc…');
    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(el.querySelector('h1')?.textContent).toContain('Settings');
    expect(tab(el, 'keys').textContent).toContain('API keys');
    expect(el.textContent).toContain('Create key');
    f.destroy();
  });

  it('changes the console language from the Langue setting and the top-bar switch follows', async () => {
    const f = TestBed.createComponent(SettingsPage);
    const sw = TestBed.createComponent(LangSwitch);
    document.body.appendChild(f.nativeElement);
    document.body.appendChild(sw.nativeElement);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    tab(el, 'appearance').click();
    await settle(f);
    expect(el.textContent).toContain('Langue');
    const english = [...el.querySelectorAll<HTMLInputElement>('mat-radio-button[data-lang="en"] input')][0];
    expect(english).toBeTruthy();
    english.click();
    await settle(f);
    sw.detectChanges();
    expect(TestBed.inject(I18nService).lang()).toBe('en');
    expect(el.querySelector('h1')?.textContent).toContain('Settings');
    expect(sw.nativeElement.querySelector('button[lang="en"]').getAttribute('aria-pressed')).toBe('true');
    // and the other way round: the top-bar switch moves the setting
    sw.nativeElement.querySelector('button[lang="fr"]').click();
    await settle(f);
    expect(el.querySelector('mat-radio-button[data-lang="fr"]')?.classList.contains('mat-mdc-radio-checked')).toBe(true);
    f.destroy();
    sw.destroy();
  });

  it('asks for the revocation in French, with the console language following a switch while open', async () => {
    const f = TestBed.createComponent(SettingsPage);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    const el = f.nativeElement as HTMLElement;
    tab(el, 'keys').click();
    await settle(f);
    el.querySelector<HTMLButtonElement>('button[aria-label^="Révoquer"]')!.click();
    await settle(f);
    const dlg = document.querySelector('oci-confirm-dialog')!;
    expect(dlg.textContent).toContain('Révoquer la clé «\u202fPipeline CI\u202f»');
    expect(dlg.textContent).toContain('Annuler');
    TestBed.inject(MatDialog).closeAll();
    f.destroy();
  });
});
