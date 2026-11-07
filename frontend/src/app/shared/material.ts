import { Directive, effect, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ErrorStateMatcher } from '@angular/material/core';
import { MatFormFieldControl, MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSortModule } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subject } from 'rxjs';

type ErrorStateControl = MatFormFieldControl<unknown> & { errorState: boolean; errorStateMatcher: ErrorStateMatcher; stateChanges: Subject<void> };

/**
 * Drives a Material control's error state from our own validation (rule draft checks, Signal Forms touched + invalid):
 * the outline turns red, aria-invalid is set and a projected <mat-error> is shown and linked by aria-describedby.
 * matInput / mat-select only recompute their error state for classic NgControl directives, so the state is pushed here.
 */
@Directive({ selector: '[ociInvalid]' })
export class InvalidState {
  readonly ociInvalid = input<boolean | null | undefined>(false);
  private readonly control = inject(MatFormFieldControl, { self: true }) as ErrorStateControl;

  constructor() {
    const invalid = () => !!this.ociInvalid();
    this.control.errorStateMatcher = { isErrorState: invalid, isSignalErrorState: invalid } as ErrorStateMatcher;
    effect(() => {
      const v = invalid();
      if (this.control.errorState !== v) {
        this.control.errorState = v;
        this.control.stateChanges.next();
      }
    });
  }
}

/** Form field, text input, select and the error-state bridge. */
export const MAT_FORM = [MatFormFieldModule, MatInputModule, MatSelectModule, InvalidState] as const;
/** Data table with sortable headers. */
export const MAT_TABLE = [MatTableModule, MatSortModule] as const;
export const MAT_BUTTONS = [MatButtonModule, MatTooltipModule] as const;
