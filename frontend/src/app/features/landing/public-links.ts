/**
 * Public project links shown on the landing page.
 *
 * The public repository does not exist yet (checked on 2026-11-09: the URL in the docs answers 404), so it is null and
 * every link derived from it (source, issues, contribution guide, documentation) is hidden instead of pointing nowhere.
 * Set it once the repository is published, e.g. 'https://github.com/<owner>/<repo>'.
 */
export const PUBLIC_REPOSITORY_URL: string | null = null;

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
