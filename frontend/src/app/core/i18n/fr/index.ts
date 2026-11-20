import { shell } from './shell';
import { overview } from './overview';
import { band } from './band';
import { verdict } from './verdict';
import { enums } from './enums';
import { errors } from './errors';
import { shared } from './shared';
import type { Dictionary } from '../i18n.service';

/** French: typed against the English areas, so a missing key does not compile. */
export const fr: Dictionary = {
  ...shell,
  ...overview,
  ...band,
  ...verdict,
  ...enums,
  ...errors,
  ...shared,
};
