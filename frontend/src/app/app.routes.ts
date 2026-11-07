import { Routes } from '@angular/router';

/** The pages load lazily, together with the Material defaults they share (see features/feature.routes.ts). */
export const routes: Routes = [{ path: '', loadChildren: () => import('./features/feature.routes').then((m) => m.FEATURE_ROUTES) }];
