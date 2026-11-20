import { HttpErrorResponse } from '@angular/common/http';
import { Key, Lang, Params, hasKey, translate } from './i18n/i18n.service';
import type { Coded } from './models';

export interface FieldError {
  /** JSON pointer or field name the error refers to, when the server provided one. */
  field: string;
  message: string;
  /** Stable code (`field.*` or `rule.*`) and its placeholder values, when the server sent them. */
  code?: string;
  params?: Record<string, unknown>;
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
    /** Stable problem code (api/codes.json) and its placeholder values; null for an older server or a client-side error. */
    readonly code: string | null = null,
    readonly params: Record<string, unknown> = {},
  ) {
    super(detail ? `${title}: ${detail}` : title);
    this.name = 'ApiError';
  }

  /** Transient failures (network, rate limit, upstream provider outage) are worth retrying. */
  get retryable(): boolean {
    return this.status === 0 || this.status === 408 || this.status === 429 || this.status >= 502;
  }
}

/** Statuses with a title of their own in the dictionaries (`error.status.<n>`); 0 is a client-side network failure. */
const TITLED = new Set([0, 400, 401, 403, 404, 409, 422, 429, 500, 502, 503, 504]);

/** Dictionary key of the title of a status: its own, else its class (5xx), else a generic "HTTP <n>" or "unexpected". */
function statusKey(status: number): Key {
  if (TITLED.has(status)) return `error.status.${status}` as Key;
  if (status >= 500) return 'error.status.500';
  return status > 0 ? 'error.status.other' : 'error.status.unexpected';
}

/** Client-side code of a network failure (no server reply); translated as `error.client.unreachable`. */
export const UNREACHABLE = 'client.unreachable';

function str(v: unknown): string | null {
  return typeof v === 'string' && v.trim() ? v : null;
}

function parseBody(body: unknown): Record<string, unknown> | null {
  // a network failure carries a ProgressEvent, whose `type` ('error') must not read as a problem type
  if (typeof Event !== 'undefined' && body instanceof Event) return null;
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

function paramsOf(v: unknown): Record<string, unknown> {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
}

/** Problem extensions commonly used for field errors: `errors` (array or map) or `invalid-params`. */
function fieldErrorsOf(p: Record<string, unknown>): FieldError[] {
  const raw = p['errors'] ?? p['invalid-params'] ?? p['invalidParams'];
  if (Array.isArray(raw)) {
    return raw
      .filter((e): e is Record<string, unknown> => !!e && typeof e === 'object')
      .map((e) => {
        const code = str(e['code']);
        return {
          field: str(e['pointer']) ?? str(e['field']) ?? str(e['name']) ?? str(e['path']) ?? '',
          message: str(e['detail']) ?? str(e['message']) ?? str(e['reason']) ?? 'Invalid value',
          ...(code ? { code, params: paramsOf(e['params']) } : {}),
        };
      });
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
    const fallbackTitle = translate('en', statusKey(status), { status });
    if (p && (str(p['title']) || str(p['detail']) || str(p['type']))) {
      return new ApiError(
        typeof p['status'] === 'number' ? (p['status'] as number) : status,
        str(p['title']) ?? fallbackTitle,
        str(p['detail']),
        str(p['type']) ?? 'about:blank',
        str(p['instance']),
        fieldErrorsOf(p),
        str(p['code']),
        paramsOf(p['params']),
      );
    }
    if (status === 0) return new ApiError(0, fallbackTitle, translate('en', 'error.client.unreachable'), 'about:blank', null, [], UNREACHABLE);
    return new ApiError(status, fallbackTitle, (p && str(p['message'])) ?? null);
  }
  const unexpected = translate('en', 'error.status.unexpected');
  if (err instanceof Error) return new ApiError(-1, unexpected, err.message);
  return new ApiError(-1, unexpected, String(err));
}

/** Placeholder values as the dictionary takes them: numbers stay numbers, lists and sets read as text. */
function textParams(params: Record<string, unknown> | undefined): Params {
  const out: Params = {};
  for (const [k, v] of Object.entries(params ?? {})) {
    out[k] = typeof v === 'number' || typeof v === 'string' ? v : Array.isArray(v) ? v.join(', ') : v === null || v === undefined ? '' : String(v);
  }
  return out;
}

/**
 * Text of a server code in the console's language, or null when this console does not know the code (a newer server):
 * the caller then shows the server's own English wording, never the raw key.
 */
export function codeText(code: string | null | undefined, params: Record<string, unknown> | undefined, lang: Lang): string | null {
  const key = `error.${code}`;
  return code && hasKey(key) ? translate(lang, key, enumParams(code, textParams(params), lang)) : null;
}

/** Params that carry an enum value, named through the enum's dictionary (`OPEN` reads "Open" / "Ouverte"). */
const ENUM_PARAMS: Record<string, Record<string, string>> = {
  'alert.already_in_status': { status: 'enum.status.' },
};

/** A value this console does not know (a newer server) stays as sent. */
function enumParams(code: string, params: Params, lang: Lang): Params {
  const named = ENUM_PARAMS[code];
  if (!named) return params;
  const out = { ...params };
  for (const [p, prefix] of Object.entries(named)) {
    const key = `${prefix}${out[p]}`;
    if (out[p] !== undefined && hasKey(key)) out[p] = translate(lang, key);
  }
  return out;
}

/** Title of an API error in the console's language, by its HTTP status (the server's title is English only). */
export function statusTitle(e: ApiError, lang: Lang): string {
  return translate(lang, statusKey(e.status), { status: e.status });
}

/** Text of a coded item (a limitation, an unreachable reason) in the console's language, else the fallback. */
export function codedText(c: Coded, lang: Lang, fallback: string): string {
  return codeText(c.code, c.params, lang) ?? fallback;
}

/** Message of an API error in the console's language: by its code, else the server's detail, else its status title. */
export function errorText(e: ApiError, lang: Lang): string {
  return codeText(e.code, e.params, lang) ?? e.detail ?? statusTitle(e, lang);
}

/** Message of one field error in the console's language: by its code, else the server's message. */
export function fieldErrorText(f: FieldError, lang: Lang): string {
  return codeText(f.code, f.params, lang) ?? f.message;
}

/** "field: message" with the colon of the language (French sets a narrow no-break space before it). */
export function fieldLine(field: string, message: string, lang: Lang): string {
  return `${field}${lang === 'fr' ? '\u202f' : ''}: ${message}`;
}

/** An API error as one text in the console's language: its message, then each field error as "field: message". */
export function apiErrorText(e: ApiError, lang: Lang): string {
  return [errorText(e, lang), ...e.fieldErrors.map((f) => fieldLine(f.field, fieldErrorText(f, lang), lang))].join(' ');
}
