import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { Component, Injector } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatButtonHarness } from '@angular/material/button/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatDialogHarness } from '@angular/material/dialog/testing';
import { provideRouter } from '@angular/router';
import { authInterceptor, errorInterceptor } from '../core/interceptors';
import { mockBackendInterceptor } from '../core/mock/mock-backend.interceptor';
import { SettingsService } from '../core/settings.service';
import { IntegrationsPage } from '../features/integrations/integrations';
import { confirmAction } from './confirm-dialog';

@Component({ template: '' })
class Empty {}

describe('confirmAction', () => {
  it('is a modal alert dialog that resolves true only on the confirming button', async () => {
    const dialog = TestBed.inject(MatDialog);
    const pending = confirmAction(dialog, TestBed.inject(Injector), { title: 'Revoke key "CI"?', message: 'Clients using it will be rejected.', confirm: 'Revoke key', danger: true });
    const loader = TestbedHarnessEnvironment.documentRootLoader(TestBed.createComponent(Empty));
    const d = await loader.getHarness(MatDialogHarness);
    expect(await d.getRole()).toBe('alertdialog');
    expect(await d.getTitleText()).toBe('Revoke key "CI"?');
    expect(document.querySelector('mat-dialog-container')?.getAttribute('aria-modal')).toBe('true');
    await (await d.getHarness(MatButtonHarness.with({ text: 'Revoke key' }))).click();
    expect(await pending).toBe(true);

    const cancelled = confirmAction(dialog, TestBed.inject(Injector), { title: 'Delete?', message: 'Gone for good.', confirm: 'Delete' });
    const d2 = await loader.getHarness(MatDialogHarness);
    await (await d2.getHarness(MatButtonHarness.with({ text: 'Cancel' }))).click();
    expect(await cancelled).toBe(false);
  });

  it('keeps a connector when the deletion is cancelled and deletes it once confirmed', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor, errorInterceptor, mockBackendInterceptor]))],
    });
    TestBed.inject(SettingsService).update({ mock: true });
    const f = TestBed.createComponent(IntegrationsPage);
    await f.whenStable();
    await new Promise((r) => setTimeout(r, 450));
    await f.whenStable();
    const page = f.componentInstance;
    const before = page.list.value()!.length;
    const loader = TestbedHarnessEnvironment.documentRootLoader(f);
    const target = page.list.value()![0];

    const cancel = page.remove(target);
    await (await (await loader.getHarness(MatDialogHarness)).getHarness(MatButtonHarness.with({ text: 'Cancel' }))).click();
    await cancel;
    expect(page.list.value()!.length).toBe(before);

    const confirm = page.remove(target);
    await (await (await loader.getHarness(MatDialogHarness)).getHarness(MatButtonHarness.with({ text: 'Delete connector' }))).click();
    await confirm;
    await new Promise((r) => setTimeout(r, 450));
    expect(page.list.value()!.map((w) => w.id)).not.toContain(target.id);
  });
});
