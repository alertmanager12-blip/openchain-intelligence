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
import { community } from './community';
import { watchlist } from './watchlist';
import { integrations } from './integrations';
import { rules } from './rules';
import { simulator } from './simulator';

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
  ...explorer,
  ...graph,
  ...traces,
  ...investigations,
  ...predictions,
  ...community,
  ...watchlist,
  ...integrations,
  ...rules,
  ...simulator,
} as const;
