import { Provider } from '@angular/core';
import { MAT_BUTTON_TOGGLE_DEFAULT_OPTIONS } from '@angular/material/button-toggle';
import { MAT_CHIPS_DEFAULT_OPTIONS } from '@angular/material/chips';
import { MAT_RIPPLE_GLOBAL_OPTIONS } from '@angular/material/core';
import { MAT_DIALOG_DEFAULT_OPTIONS, MatDialogConfig } from '@angular/material/dialog';
import { MAT_FORM_FIELD_DEFAULT_OPTIONS } from '@angular/material/form-field';
import { MAT_TABS_CONFIG } from '@angular/material/tabs';
import { MAT_TOOLTIP_DEFAULT_OPTIONS } from '@angular/material/tooltip';

/**
 * Material defaults for the pages: outlined fields whose subscript only takes room when there is a hint or an error
 * (dense forms), start-aligned tabs, segmented toggles without the check mark. Provided by the lazy feature routes
 * (features/feature.routes.ts) and the unit tests, never from app.config.ts: importing these tokens pulls each component
 * module into the bundle, and the shell does not need them. Dialogs opened by a page receive the page injector.
 */
export const MATERIAL_DEFAULTS: Provider[] = [
  // flat console: hover and focus state layers stay, the ink ripple is off (it reads as stock Material)
  { provide: MAT_RIPPLE_GLOBAL_OPTIONS, useValue: { disabled: true } },
  { provide: MAT_FORM_FIELD_DEFAULT_OPTIONS, useValue: { appearance: 'outline', subscriptSizing: 'dynamic', floatLabel: 'always' } },
  { provide: MAT_BUTTON_TOGGLE_DEFAULT_OPTIONS, useValue: { hideSingleSelectionIndicator: true, hideMultipleSelectionIndicator: true } },
  { provide: MAT_TABS_CONFIG, useValue: { stretchTabs: false, alignTabs: 'start', animationDuration: '0ms' } },
  { provide: MAT_TOOLTIP_DEFAULT_OPTIONS, useValue: { showDelay: 400, hideDelay: 0, touchendHideDelay: 1500, disableTooltipInteractivity: true } },
  { provide: MAT_CHIPS_DEFAULT_OPTIONS, useValue: { separatorKeyCodes: [], hideSingleSelectionIndicator: true } },
  // the provided object replaces MatDialogConfig's defaults (role, ...), so start from them
  { provide: MAT_DIALOG_DEFAULT_OPTIONS, useValue: { ...new MatDialogConfig(), autoFocus: 'first-tabbable', restoreFocus: true, hasBackdrop: true, ariaModal: true, maxWidth: 'calc(100vw - 16px)' } },
];
