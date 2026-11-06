export interface NavItem {
  path: string;
  label: string;
  exact?: boolean;
  /** SVG path data on a 16x16 grid, stroked. */
  icon: string;
  /** Extra words the command palette matches on. */
  keywords?: string;
}

export interface NavGroup {
  title: string;
  items: NavItem[];
}

/** Grouped by the analyst's job. Shared by the rail and the command palette. */
export const NAV: NavGroup[] = [
  { title: '', items: [{ path: '/', label: 'Overview', exact: true, icon: 'M2.5 2.5h4.5v5h-4.5zM9 2.5h4.5v3H9zM9 7.5h4.5v6H9zM2.5 9.5h4.5v4h-4.5z', keywords: 'dashboard home health' }] },
  { title: 'Investigate', items: [
    { path: '/explorer', label: 'Explorer', icon: 'M7 2.5a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9zM10.3 10.3 13.5 13.5', keywords: 'search transaction address lookup' },
    { path: '/risk', label: 'Wallet risk', icon: 'M2 12.5a6 6 0 0 1 12 0M8 12.5 10.8 7', keywords: 'score contributions' },
    { path: '/investigation', label: 'Fund flow graph', icon: 'M4 4.5a1.5 1.5 0 1 0 0-.01M12 4a1.5 1.5 0 1 0 0-.01M8 12.5a1.5 1.5 0 1 0 0-.01M5.3 4.6l5.2-.4M4.8 5.8l2.4 5.2M11.4 5.4 8.9 11', keywords: 'graph trace investigation flows' },
    { path: '/community', label: 'Community labels', icon: 'M2.5 3.5h6l5 5-5 5-5-5zM5.5 6.5h.01', keywords: 'labels report tags' },
  ] },
  { title: 'Monitor', items: [
    { path: '/alerts', label: 'Alerts', icon: 'M4 11V7a4 4 0 0 1 8 0v4l1.5 1.5h-11zM6.5 14h3', keywords: 'triage incidents' },
    { path: '/rules/builder', label: 'Rule builder', icon: 'M2.5 4h7M2.5 8h11M2.5 12h5M11 2.5v3M5.5 6.5v3M9.5 10.5v3', keywords: 'create rule conditions' },
    { path: '/rules/code', label: 'Rules as code', icon: 'M5.5 4 2 8l3.5 4M10.5 4 14 8l-3.5 4', keywords: 'yaml import export stored rules enable' },
    { path: '/rules/simulator', label: 'Rule simulator', icon: 'M4 2.5v11l9-5.5z', keywords: 'dry run test replay' },
  ] },
  { title: 'Configure', items: [
    { path: '/integrations', label: 'Integrations', icon: 'M6 2.5v3M10 2.5v3M4.5 5.5h7v2.5a3.5 3.5 0 0 1-7 0zM8 11.5v2', keywords: 'webhook slack discord telegram connectors' },
    { path: '/settings', label: 'Settings', icon: 'M8 5.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5zM8 1.5v2M8 12.5v2M1.5 8h2M12.5 8h2M3.4 3.4l1.4 1.4M11.2 11.2l1.4 1.4M3.4 12.6l1.4-1.4M11.2 4.8l1.4-1.4', keywords: 'api keys users theme connection' },
  ] },
];

export const NAV_ITEMS: NavItem[] = NAV.flatMap((g) => g.items);
