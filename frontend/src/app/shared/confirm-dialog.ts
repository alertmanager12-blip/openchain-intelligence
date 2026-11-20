import { ChangeDetectionStrategy, Component, Injector, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { I18nService, type Key, type Params } from '../core/i18n/i18n.service';

/** A text of the dialog: a dictionary key (follows a language switch while the dialog is open) or a ready string. */
export type DialogText = string | { key: Key; params?: Params };

export interface ConfirmData {
  title: DialogText;
  message: DialogText;
  /** Verb of the action, repeated on the confirming button: "Revoke key"; "Confirm" when absent. */
  confirm?: DialogText;
  danger?: boolean;
}

/** Small confirmation dialog for destructive actions (replaces window.confirm). Focus starts on Cancel. */
@Component({
  selector: 'oci-confirm-dialog',
  imports: [MatDialogModule, MatButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>{{ text(data.title) }}</h2>
    <mat-dialog-content><p>{{ text(data.message) }}</p></mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton type="button" [mat-dialog-close]="false" cdkFocusInitial>{{ i18n.t('dialog.cancel') }}</button>
      <button matButton="filled" type="button" [class.danger]="data.danger" [mat-dialog-close]="true">{{ data.confirm ? text(data.confirm) : i18n.t('dialog.confirm') }}</button>
    </mat-dialog-actions>
  `,
  styles: `
    :host { display: block; }
    p { margin: 0; color: var(--ink-2); max-width: 52ch; }
    h2 { font: 600 20px/1.25 var(--font-display); }
  `,
})
export class ConfirmDialog {
  protected readonly data = inject<ConfirmData>(MAT_DIALOG_DATA);
  protected readonly i18n = inject(I18nService);
  protected text(t: DialogText): string { return typeof t === 'string' ? t : this.i18n.t(t.key, t.params); }
}

/**
 * Opens the confirmation dialog from a page (call it in an injection context's lifetime, e.g. a click handler of a page
 * that captured inject(Injector)); resolves true only when the action is confirmed.
 */
export async function confirmAction(dialog: MatDialog, injector: Injector, data: ConfirmData): Promise<boolean> {
  const ref = dialog.open<ConfirmDialog, ConfirmData, boolean>(ConfirmDialog, { data, injector, width: '440px', maxWidth: 'calc(100vw - 32px)', role: 'alertdialog', autoFocus: 'dialog', ariaModal: true });
  return (await firstValueFrom(ref.afterClosed())) === true;
}
