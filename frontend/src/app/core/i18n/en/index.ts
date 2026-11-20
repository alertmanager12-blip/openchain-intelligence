import { shell } from './shell';
import { overview } from './overview';
import { band } from './band';
import { verdict } from './verdict';
import { enums } from './enums';
import { errors } from './errors';
import { shared } from './shared';

/** English: the source of the keys, one file per area (fr/ must define every one). */
export const en = {
  ...shell,
  ...overview,
  ...band,
  ...verdict,
  ...enums,
  ...errors,
  ...shared,
} as const;
