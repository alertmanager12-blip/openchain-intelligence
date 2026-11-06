import { HttpErrorResponse } from '@angular/common/http';
import { ApplicationRef, Injector, runInInjectionContext, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiError } from '../core/api-error';
import { apiResource } from './api-resource';

describe('apiResource', () => {
  const stable = () => TestBed.inject(ApplicationRef).whenStable();
  const make = <T, P>(params: () => P | undefined, stream: (p: P) => Observable<T>, keepPrevious = false) =>
    runInInjectionContext(TestBed.inject(Injector), () => apiResource({ params, stream, keepPrevious }));

  it('stays idle and sends nothing while params are undefined', async () => {
    const calls: number[] = [];
    const r = make<number, number>(() => undefined, (p) => { calls.push(p); return of(p); });
    await stable();
    expect(r.status()).toBe('idle');
    expect(r.value()).toBeUndefined();
    expect(calls).toEqual([]);
  });

  it('loads, follows params and exposes the value without throwing', async () => {
    const id = signal(1);
    const r = make(() => ({ id: id() }), (p) => of(p.id * 10));
    await stable();
    expect(r.value()).toBe(10);
    id.set(2);
    await stable();
    expect(r.value()).toBe(20);
    expect(r.loading()).toBe(false);
  });

  it('maps failures to ApiError and keeps value() safe in the error state', async () => {
    const r = make(() => ({}), () => throwError(() => new HttpErrorResponse({ status: 503, error: { title: 'Provider unavailable', detail: 'down' } })));
    await stable();
    expect(r.status()).toBe('error');
    expect(r.error()).toBeInstanceOf(ApiError);
    expect(r.error()?.title).toBe('Provider unavailable');
    expect(r.error()?.retryable).toBe(true);
    expect(() => r.value()).not.toThrow();
    expect(r.value()).toBeUndefined();
  });

  it('keeps the previous value while new params load when keepPrevious is set', async () => {
    const page = signal(1);
    const pending = new Subject<string>();
    const r = make(() => page(), (p) => (p === 1 ? of('first') : pending), true);
    await stable();
    expect(r.value()).toBe('first');
    page.set(2);
    TestBed.tick();
    expect(r.loading()).toBe(true);
    expect(r.value()).toBe('first');
    pending.next('second');
    pending.complete();
    await stable();
    expect(r.value()).toBe('second');
  });

  it('accepts local writes after a mutation', async () => {
    const r = make(() => ({}), () => of([1, 2]));
    await stable();
    r.update((l) => [...(l ?? []), 3]);
    expect(r.value()).toEqual([1, 2, 3]);
    expect(r.status()).toBe('local');
  });
});
