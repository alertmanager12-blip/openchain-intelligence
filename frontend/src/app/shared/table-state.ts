import { Signal, computed, signal } from '@angular/core';
import { Sort } from '@angular/material/sort';

export type SortDir = 'asc' | 'desc';

/** Client-side filter + sort over a signal of rows, driven by a matSort header (onSort). Accessors keep sorting type-aware (raw amounts as BigInt). */
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

  /** From a matSort (matSortChange); a cleared sort falls back to no ordering. */
  onSort(e: Sort): void {
    this.sortKey.set(e.direction ? e.active : null);
    this.sortDir.set(e.direction || 'asc');
  }
}
