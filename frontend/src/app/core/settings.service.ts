import { Injectable, signal, effect } from '@angular/core';
import { environment } from '../../environments/environment';

export type AuthMode = 'none' | 'bearer' | 'apiKey';

export interface ConnectionSettings {
  authMode: AuthMode;
  token: string;
  mock: boolean;
}

const KEY = 'oci.settings.v1';

export function readStorage<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? { ...fallback, ...(JSON.parse(raw) as T) } : fallback;
  } catch {
    return fallback;
  }
}

export function writeStorage(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* storage unavailable (private mode): settings simply do not persist */
  }
}

/** Client-side connection settings (credentials + mock toggle), persisted per browser. */
@Injectable({ providedIn: 'root' })
export class SettingsService {
  readonly connection = signal<ConnectionSettings>(
    readStorage<ConnectionSettings>(KEY, { authMode: 'none', token: '', mock: environment.mockApi }),
  );

  constructor() {
    effect(() => writeStorage(KEY, this.connection()));
  }

  update(patch: Partial<ConnectionSettings>): void {
    this.connection.update((c) => ({ ...c, ...patch }));
  }
}
