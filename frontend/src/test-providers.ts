import { Provider } from '@angular/core';
import { MATERIAL_ANIMATIONS } from '@angular/material/core';
import { MATERIAL_DEFAULTS } from './app/material-defaults';

/** Unit-test environment: the app's Material defaults, with Material animations off so overlays open and close synchronously. */
export default [...MATERIAL_DEFAULTS, { provide: MATERIAL_ANIMATIONS, useValue: { animationsDisabled: true } }] satisfies Provider[];
