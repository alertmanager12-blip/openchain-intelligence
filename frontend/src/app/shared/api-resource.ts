import { Signal, computed, linkedSignal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Observable } from 'rxjs';
import { ApiError, toApiError } from '../core/api-error';

export interface ApiResource<T> {
  /** The loaded value; undefined while idle, loading or in error (never throws, unlike Resource.value()). */
  readonly value: Signal<T | undefined>;
  readonly loading: Signal<boolean>;
  /** Every failure normalised to ApiError (errorInterceptor already maps HTTP failures; this covers the rest). */
  readonly error: Signal<ApiError | null>;
  /** 'idle' when params() returned undefined: nothing was requested. */
  readonly status: Signal<'idle' | 'loading' | 'reloading' | 'resolved' | 'error' | 'local'>;
  reload(): void;
  /** Local write after a mutation (status becomes 'local'). */
  set(value: T): void;
  update(fn: (value: T | undefined) => T): void;
}

export interface ApiResourceOptions<T, P> {
  /** Reactive request parameters. Return undefined to skip the request. */
  params: () => P | undefined;
  /** The typed ApiService call for these params. */
  stream: (params: P) => Observable<T>;
  /** Keep showing the previous value while new params load (filters, pagination) instead of resetting to a skeleton. */
  keepPrevious?: boolean;
}

/**
 * Typed read resource over ApiService, built on Angular's rxResource: requests follow params() reactively,
 * stale requests are cancelled when params change, the component's PendingTasks track loading (so tests can
 * whenStable()), and errors arrive as ApiError.
 */
export function apiResource<T, P>(opts: ApiResourceOptions<T, P>): ApiResource<T> {
  const ref = rxResource<T, P | undefined>({
    params: opts.params,
    stream: ({ params }) => opts.stream(params as P),
  });
  const current = computed(() => (ref.hasValue() ? ref.value() : undefined));
  const value = linkedSignal<{ v: T | undefined; loading: boolean }, T | undefined>({
    source: () => ({ v: current(), loading: ref.isLoading() }),
    computation: (src, prev) => (src.v !== undefined ? src.v : opts.keepPrevious && src.loading ? prev?.value : undefined),
  });
  const error = computed(() => {
    const e = ref.error();
    return e ? toApiError(e) : null;
  });
  return {
    value,
    loading: ref.isLoading,
    error,
    status: ref.status,
    reload: () => void ref.reload(),
    set: (v) => ref.value.set(v),
    update: (fn) => ref.value.set(fn(current())),
  };
}
