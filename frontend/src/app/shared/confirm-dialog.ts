import { ChangeDetectionStrategy, Component, Injector, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';

export interface ConfirmData {
  title: string;
  message: string;
  /** Verb of the action, repeated on the confirming button: "Revoke key". */
  confirm: string;
  danger?: boolean;
}

/** Small confirmation dialog for destructive actions (replaces window.confirm). Focus starts on Cancel. */
@Component({
  selector: 'oci-confirm-dialog',
  imports: [MatDialogModule, MatButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content><p>{{ data.message }}</p></mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton type="button" [mat-dialog-close]="false" cdkFocusInitial>Cancel</button>
      <button matButton="filled" type="button" [class.danger]="data.danger" [mat-dialog-close]="true">{{ data.confirm }}</button>
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
}

/**
 * Opens the confirmation dialog from a page (call it in an injection context's lifetime, e.g. a click handler of a page
 * that captured inject(Injector)); resolves true only when the action is confirmed.
 */
export async function confirmAction(dialog: MatDialog, injector: Injector, data: ConfirmData): Promise<boolean> {
  const ref = dialog.open<ConfirmDialog, ConfirmData, boolean>(ConfirmDialog, { data, injector, width: '440px', maxWidth: 'calc(100vw - 32px)', role: 'alertdialog', autoFocus: 'dialog', ariaModal: true });
  return (await firstValueFrom(ref.afterClosed())) === true;
}
