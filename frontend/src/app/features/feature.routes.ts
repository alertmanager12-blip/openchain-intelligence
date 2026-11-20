import { Routes } from '@angular/router';
import { MATERIAL_DEFAULTS } from '../material-defaults';

/**
 * Every page, under one parent route whose environment injector carries the Material defaults (loaded lazily).
 * A `title` is a dictionary key: core/i18n/title-strategy.ts writes it in the console language.
 */
export const FEATURE_ROUTES: Routes = [
  { path: '', providers: MATERIAL_DEFAULTS, children: [
  { path: 'overview', title: 'nav.overview', loadComponent: () => import('./overview/overview').then((m) => m.OverviewPage) },
  { path: 'explorer', title: 'nav.explorer', loadComponent: () => import('./explorer/explorer').then((m) => m.ExplorerPage) },
  { path: 'wallets/:network/:address', title: 'wallet.docTitle', loadComponent: () => import('./wallet/wallet').then((m) => m.WalletPage) },
  { path: 'risk', title: 'nav.risk', loadComponent: () => import('./risk/risk').then((m) => m.RiskPage) },
  { path: 'risk/:network/:address', title: 'nav.risk', loadComponent: () => import('./risk/risk').then((m) => m.RiskPage) },
  { path: 'investigation', title: 'nav.graph', loadComponent: () => import('./investigation/investigation').then((m) => m.InvestigationPage) },
  { path: 'traces', title: 'nav.traces', loadComponent: () => import('./traces/traces').then((m) => m.TracesPage) },
  { path: 'predictions', title: 'nav.predictions', loadComponent: () => import('./predictions/predictions').then((m) => m.PredictionsPage) },
  { path: 'investigations', title: 'nav.investigations', loadComponent: () => import('./investigations/investigations').then((m) => m.InvestigationsPage) },
  { path: 'alerts', title: 'nav.alerts', loadComponent: () => import('./alerts/alerts').then((m) => m.AlertsPage) },
  { path: 'rules/builder', title: 'nav.ruleBuilder', loadComponent: () => import('./rule-builder/rule-builder').then((m) => m.RuleBuilderPage) },
  { path: 'rules/code', title: 'nav.rulesCode', loadComponent: () => import('./rule-code/rule-code').then((m) => m.RuleCodePage) },
  { path: 'rules/simulator', title: 'nav.ruleSimulator', loadComponent: () => import('./simulator/simulator').then((m) => m.SimulatorPage) },
  { path: 'watchlist', title: 'nav.watchlist', loadComponent: () => import('./watchlist/watchlist').then((m) => m.WatchlistPage) },
  { path: 'integrations', title: 'nav.integrations', loadComponent: () => import('./integrations/integrations').then((m) => m.IntegrationsPage) },
  { path: 'community', title: 'nav.community', loadComponent: () => import('./community/community').then((m) => m.CommunityPage) },
  { path: 'settings', title: 'nav.settings', loadComponent: () => import('./settings/settings').then((m) => m.SettingsPage) },
  { path: '**', redirectTo: '' },
  ] },
];
