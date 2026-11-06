import { DestroyRef, Signal, computed, inject, signal, untracked } from '@angular/core';
import { Observable, Subscription } from 'rxjs';
import { ApiError, toApiError } from '../core/api-error';

export type QueryStatus = 'idle' | 'loading' | 'success' | 'error';

/** Small signal-based async state holder: loading / data / error, with cancellation of stale requests. */
export class Query<T> {
  readonly status = signal<QueryStatus>('idle');
  readonly data = signal<T | undefined>(undefined);
  readonly error = signal<ApiError | null>(null);
  readonly loading: Signal<boolean> = computed(() => this.status() === 'loading');
  private sub?: Subscription;
  private last?: () => Observable<T>;

  /** Safe to call from an effect: the request is issued untracked, so it never becomes a dependency. */
  run(factory: () => Observable<T>, keepData = true): void {
    untracked(() => this.exec(factory, keepData));
  }

  private exec(factory: () => Observable<T>, keepData: boolean): void {
    this.last = factory;
    this.sub?.unsubscribe();
    this.status.set('loading');
    this.error.set(null);
    if (!keepData) this.data.set(undefined);
    this.sub = factory().subscribe({
      next: (d) => {
        this.data.set(d);
        this.status.set('success');
      },
      error: (e: unknown) => {
        this.error.set(toApiError(e));
        this.status.set('error');
      },
    });
  }

  retry(): void {
    if (this.last) this.run(this.last);
  }

  destroy(): void {
    this.sub?.unsubscribe();
  }
}

/** Creates a Query bound to the current injection context (cancelled on destroy). */
export function query<T>(): Query<T> {
  const q = new Query<T>();
  inject(DestroyRef).onDestroy(() => q.destroy());
  return q;
}

export type SortDir = 'asc' | 'desc';

/** Client-side filter + sort over a signal of rows. Accessors keep sorting type-aware (raw amounts as BigInt). */
export class TableState<T> {
  readonly sortKey = signal<string | null>(null);
  readonly sortDir = signal<SortDir>('asc');
  readonly filter = signal('');

  constructor(
    private readonly rows: Signal<T[]>,
    private readonly accessors: Record<string, (r: T) => string | number | bigint | null>,
    private readonly searchText: (r: T) => string,
    initialSort?: { key: string; dir: SortDir },
  ) {
    if (initialSort) {
      this.sortKey.set(initialSort.key);
      this.sortDir.set(initialSort.dir);
    }
  }

  readonly view = computed(() => {
    const f = this.filter().trim().toLowerCase();
    let out = f ? this.rows().filter((r) => this.searchText(r).toLowerCase().includes(f)) : [...this.rows()];
    const key = this.sortKey();
    const acc = key ? this.accessors[key] : undefined;
    if (acc) {
      const dir = this.sortDir() === 'asc' ? 1 : -1;
      out = out.sort((a, b) => {
        const x = acc(a);
        const y = acc(b);
        if (x === y) return 0;
        if (x === null) return 1;
        if (y === null) return -1;
        return (x < y ? -1 : 1) * dir;
      });
    }
    return out;
  });

  sort(key: string): void {
    if (this.sortKey() === key) this.sortDir.update((d) => (d === 'asc' ? 'desc' : 'asc'));
    else {
      this.sortKey.set(key);
      this.sortDir.set('asc');
    }
  }

  aria(key: string): 'ascending' | 'descending' | 'none' {
    return this.sortKey() !== key ? 'none' : this.sortDir() === 'asc' ? 'ascending' : 'descending';
  }

  icon(key: string): string {
    return this.sortKey() !== key ? '↕' : this.sortDir() === 'asc' ? '↑' : '↓';
  }
}
