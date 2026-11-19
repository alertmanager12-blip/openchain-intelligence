import type { Key } from '../core/i18n/i18n.service';

export interface NavItem {
  path: string;
  /** English name (tests, fallbacks); the screen shows {@link labelKey} in the console's language. */
  label: string;
  labelKey: Key;
  exact?: boolean;
  /** SVG path data on a 16x16 grid, stroked. */
  icon: string;
  /** Extra words the command palette matches on. */
  keywords?: string;
  /** Second key of the "G then key" go-to shortcut. */
  key?: string;
  /** Count shown next to the label in the sidebar (filled from GET /dashboard by the shell). */
  count?: 'openAlerts' | 'rulesEnabled';
}

export interface NavGroup {
  title: string;
  titleKey: Key;
  items: NavItem[];
}

/** Grouped by the analyst's job. Shared by the rail and the command palette. */
export const NAV: NavGroup[] = [
  { title: '', titleKey: 'nav.group.home', items: [{ path: '/overview', label: 'Overview', labelKey: 'nav.overview', exact: true, key: 'o', icon: 'M2.5 2.5h4.5v5h-4.5zM9 2.5h4.5v3H9zM9 7.5h4.5v6H9zM2.5 9.5h4.5v4h-4.5z', keywords: 'dashboard home health accueil santé tableau de bord' }] },
  { title: 'Investigate', titleKey: 'nav.group.investigate', items: [
    { path: '/explorer', label: 'Explorer', labelKey: 'nav.explorer', key: 'e', icon: 'M7 2.5a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9zM10.3 10.3 13.5 13.5', keywords: 'search transaction address lookup recherche transaction adresse' },
    { path: '/risk', label: 'Wallet risk', labelKey: 'nav.risk', key: 'r', icon: 'M2 12.5a6 6 0 0 1 12 0M8 12.5 10.8 7', keywords: 'score contributions score contributions' },
    { path: '/investigation', label: 'Fund flow graph', labelKey: 'nav.graph', key: 'f', icon: 'M4 4.5a1.5 1.5 0 1 0 0-.01M12 4a1.5 1.5 0 1 0 0-.01M8 12.5a1.5 1.5 0 1 0 0-.01M5.3 4.6l5.2-.4M4.8 5.8l2.4 5.2M11.4 5.4 8.9 11', keywords: 'graph trace investigation flows graphe traçage enquête flux' },
    { path: '/traces', label: 'Stored traces', labelKey: 'nav.traces', key: 't', icon: 'M3 2.5h7l3 3v8H3zM10 2.5v3h3M5.5 8h5M5.5 10.5h5', keywords: 'trace saved investigation versions evidence traçage enregistré versions preuves' },
    { path: '/investigations', label: 'Investigations', labelKey: 'nav.investigations', key: 'v', icon: 'M3 2.5h10v11H3zM5.5 5.5h5M5.5 8h5M5.5 10.5h3', keywords: 'report assertions plan evidence rapport assertions plan preuves' },
    { path: '/predictions', label: 'Predictions & what-if', labelKey: 'nav.predictions', key: 'p', icon: 'M2.5 12.5 6 8l3 2.5 4.5-6M10.5 4.5h3v3', keywords: 'predicted simulated hypothesis expiry rate what-if prédit simulé hypothèse' },
    { path: '/community', label: 'Community labels', labelKey: 'nav.community', key: 'l', icon: 'M2.5 3.5h6l5 5-5 5-5-5zM5.5 6.5h.01', keywords: 'labels report tags étiquettes signalement' },
  ] },
  { title: 'Monitor', titleKey: 'nav.group.monitor', items: [
    { path: '/alerts', label: 'Alerts', labelKey: 'nav.alerts', key: 'a', count: 'openAlerts', icon: 'M4 11V7a4 4 0 0 1 8 0v4l1.5 1.5h-11zM6.5 14h3', keywords: 'triage incidents alertes tri incidents' },
    { path: '/rules/builder', label: 'Rule builder', labelKey: 'nav.ruleBuilder', key: 'b', icon: 'M2.5 4h7M2.5 8h11M2.5 12h5M11 2.5v3M5.5 6.5v3M9.5 10.5v3', keywords: 'create rule conditions créer règle conditions' },
    { path: '/rules/code', label: 'Rules as code', labelKey: 'nav.rulesCode', key: 'c', count: 'rulesEnabled', icon: 'M5.5 4 2 8l3.5 4M10.5 4 14 8l-3.5 4', keywords: 'yaml import export stored rules enable yaml importer exporter règles' },
    { path: '/rules/simulator', label: 'Rule simulator', labelKey: 'nav.ruleSimulator', key: 'd', icon: 'M4 2.5v11l9-5.5z', keywords: 'dry run test replay essai rejouer' },
    { path: '/watchlist', label: 'Watchlist', labelKey: 'nav.watchlist', key: 'w', icon: 'M1.5 8s2.5-4.5 6.5-4.5S14.5 8 14.5 8 12 12.5 8 12.5 1.5 8 1.5 8zM8 6.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z', keywords: 'own wallets deposit treasury monitored scope portefeuilles propres dépôt trésorerie' },
  ] },
  { title: 'Configure', titleKey: 'nav.group.configure', items: [
    { path: '/integrations', label: 'Integrations', labelKey: 'nav.integrations', key: 'i', icon: 'M6 2.5v3M10 2.5v3M4.5 5.5h7v2.5a3.5 3.5 0 0 1-7 0zM8 11.5v2', keywords: 'webhook slack discord telegram connectors webhook connecteurs' },
    { path: '/settings', label: 'Settings', labelKey: 'nav.settings', key: 's', icon: 'M8 5.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5zM8 1.5v2M8 12.5v2M1.5 8h2M12.5 8h2M3.4 3.4l1.4 1.4M11.2 11.2l1.4 1.4M3.4 12.6l1.4-1.4M11.2 4.8l1.4-1.4', keywords: 'api keys users theme connection clés api utilisateurs thème connexion langue' },
  ] },
];

export const NAV_ITEMS: NavItem[] = NAV.flatMap((g) => g.items);
