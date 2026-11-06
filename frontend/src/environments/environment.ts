/** Production defaults: talk to the real backend through /api/v1. */
export const environment = {
  apiBaseUrl: '/api/v1',
  /** When true, an HTTP interceptor serves in-memory fake data instead of calling the backend. */
  mockApi: false,
};
