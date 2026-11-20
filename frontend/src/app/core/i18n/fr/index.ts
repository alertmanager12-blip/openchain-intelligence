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
import { explorer } from './explorer';
import { graph } from './graph';
import { traces } from './traces';
import { investigations } from './investigations';
import { predictions } from './predictions';
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
  ...alerts,
  ...wallet,
  ...risk,
  ...explorer,
  ...graph,
  ...traces,
  ...investigations,
  ...predictions,
};
