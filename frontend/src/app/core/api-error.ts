import { HttpErrorResponse } from '@angular/common/http';

export interface FieldError {
  /** JSON pointer or field name the error refers to, when the server provided one. */
  field: string;
  message: string;
}

/** Normalised API error, built from an RFC 9457 problem+json response when available. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly title: string,
    readonly detail: string | null,
    readonly type: string = 'about:blank',
    readonly instance: string | null = null,
    readonly fieldErrors: FieldError[] = [],
  ) {
    super(detail ? `${title}: ${detail}` : title);
    this.name = 'ApiError';
  }

  /** Transient failures (network, rate limit, upstream provider outage) are worth retrying. */
  get retryable(): boolean {
    return this.status === 0 || this.status === 408 || this.status === 429 || this.status >= 502;
  }
}

const DEFAULT_TITLES: Record<number, string> = {
  0: 'Backend unreachable',
  400: 'Invalid request',
  401: 'Authentication required',
  403: 'Not allowed',
  404: 'Not found',
  409: 'Conflict',
  422: 'Validation failed',
  429: 'Too many requests',
  500: 'Server error',
  502: 'Upstream provider error',
  503: 'Service unavailable',
  504: 'Upstream provider timeout',
};

function str(v: unknown): string | null {
  return typeof v === 'string' && v.trim() ? v : null;
}

function parseBody(body: unknown): Record<string, unknown> | null {
  if (body && typeof body === 'object' && !(body instanceof Blob) && !(body instanceof ArrayBuffer)) return body as Record<string, unknown>;
  if (typeof body === 'string') {
    try {
      const parsed: unknown = JSON.parse(body);
      return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null;
    } catch {
      return null;
    }
  }
  return null;
}

/** Problem extensions commonly used for field errors: `errors` (array or map) or `invalid-params`. */
function fieldErrorsOf(p: Record<string, unknown>): FieldError[] {
  const raw = p['errors'] ?? p['invalid-params'] ?? p['invalidParams'];
  if (Array.isArray(raw)) {
    return raw
      .filter((e): e is Record<string, unknown> => !!e && typeof e === 'object')
      .map((e) => ({
        field: str(e['pointer']) ?? str(e['field']) ?? str(e['name']) ?? str(e['path']) ?? '',
        message: str(e['detail']) ?? str(e['message']) ?? str(e['reason']) ?? 'Invalid value',
      }));
  }
  if (raw && typeof raw === 'object') {
    return Object.entries(raw as Record<string, unknown>).map(([field, m]) => ({ field, message: String(m) }));
  }
  return [];
}

/** Maps anything thrown by HttpClient (or elsewhere) to an ApiError. Never throws. */
export function toApiError(err: unknown): ApiError {
  if (err instanceof ApiError) return err;
  if (err instanceof HttpErrorResponse) {
    const status = err.status ?? 0;
    const p = parseBody(err.error);
    const fallbackTitle = DEFAULT_TITLES[status] ?? (status >= 500 ? 'Server error' : `HTTP ${status}`);
    if (p && (str(p['title']) || str(p['detail']) || str(p['type']))) {
      return new ApiError(
        typeof p['status'] === 'number' ? (p['status'] as number) : status,
        str(p['title']) ?? fallbackTitle,
        str(p['detail']),
        str(p['type']) ?? 'about:blank',
        str(p['instance']),
        fieldErrorsOf(p),
      );
    }
    const detail = status === 0 ? 'The API could not be reached. Check your connection or the backend URL.' : (p && str(p['message'])) ?? null;
    return new ApiError(status, fallbackTitle, detail);
  }
  if (err instanceof Error) return new ApiError(-1, 'Unexpected error', err.message);
  return new ApiError(-1, 'Unexpected error', String(err));
}
