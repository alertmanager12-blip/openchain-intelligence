/**
 * Public project links shown on the landing page.
 *
 * Every link derived from it (source, issues, contribution guide, documentation, licence) is hidden when it is null, instead
 * of pointing nowhere (e.g. for a private fork).
 */
export const PUBLIC_REPOSITORY_URL: string | null = 'https://github.com/alertmanager12-blip/openchain-intelligence';

export interface PublicLinks {
  readonly repository: string | null;
  readonly issues: string | null;
  readonly contributing: string | null;
  readonly docs: string | null;
  readonly license: string | null;
}

/** Links derived from the repository URL (GitHub layout); all null when no repository is configured. */
export function publicLinks(repository: string | null = PUBLIC_REPOSITORY_URL): PublicLinks {
  const base = repository?.replace(/\/+$/, '') || null;
  const file = (path: string) => (base ? `${base}/blob/main/${path}` : null);
  return {
    repository: base,
    issues: base ? `${base}/issues` : null,
    contributing: file('CONTRIBUTING.md'),
    docs: base ? `${base}/tree/main/docs` : null,
    license: file('LICENSE'),
  };
}
