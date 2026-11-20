import { shell } from './shell';
import { overview } from './overview';
import { band } from './band';
import { verdict } from './verdict';
import { enums } from './enums';
import { errors } from './errors';
import { shared } from './shared';
import { alerts } from './alerts';
import { wallet } from './wallet';
import { risk } from './risk';

/** English: the source of the keys, one file per area (fr/ must define every one). */
export const en = {
  ...shell,
  ...overview,
  ...band,
  ...verdict,
  ...enums,
  ...errors,
  ...shared,
  ...alerts,
  ...wallet,
  ...risk,
} as const;
