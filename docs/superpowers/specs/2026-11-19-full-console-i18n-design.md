# Whole console in English and French (option C, architecture 1)

- Date: 2026-11-19
- Status: proposed, awaiting the maintainer's approval
- Scope: `frontend/` (every screen), `oci-app` (API error codes, limitation codes), `oci-alerting` (rule schema), `rules/`
- Builds on: `2026-11-19-home-bilingual-design.md` (shipped in PR #37: `I18nService`, typed `en`/`fr` dictionaries, `Intl`
  formatting, FR | EN switch, overview, shell, verdict pieces, `alertSentence`)

## Intent and why

The maintainer works in French and asked for every English text of the console to exist in English and French, "with the
best possible architecture". PR #37 translated the shell and the overview; the 16 other screens, the shared components
and the public landing page are still English (about 1,200 strings), and several texts come from the server in English.

Success: with French chosen, no English sentence appears anywhere in the console except (a) sentences the engine composes
from evidence (see "Engine sentences" below), always labelled as the engine's wording, (b) technical identifiers (addresses, hashes, rule field paths, YAML, connector types,
token symbols, product and brand names). Adding a screen or a server error later cannot silently ship English: a test or
the compiler fails.

## Decisions taken with the maintainer

1. **Option C** for server texts: the interface and the server's *fixed* texts are translated (API errors, network
   limitations, shipped rules' name / description / documentation); stored engine sentences (signal and alert summaries,
   evidence descriptions) keep the engine's wording, shown on demand, except where a sentence builder already exists
   (`alertSentence`, signal type names).
2. **Architecture 1**: translations live in the console only. The server sends stable codes and parameters; shipped rule
   texts carry their French in the rule YAML. Rejected: a server that answers in the requested language (two places to
   keep in sync, missing keys found only at runtime); a third-party library (new dependency, untyped keys).

## Engine sentences (Claude's call, following option C)

Option C keeps the engine's own sentences. That covers every text the engine *composes from evidence*, stored or computed:
signal and alert summaries, contribution summaries, evidence descriptions, the risk score's `methodSummary`, simulator
and prediction explanations, `ignoredReason`. On every screen:
- what has a structured name is translated by the console: signal types, contribution factors, evidence kinds, label
  categories, levels, severities, statuses, rule names;
- the composed sentence is shown as it is, in English, inside an element marked `lang="en"` and introduced by a French
  label ("Formulation du moteur :"), visible, not hidden, when it is the main content of a block (for example
  `methodSummary`), behind the existing "Formulation du moteur" disclosure when a French sentence already says the same
  thing (overview verdicts);
- adding codes for these sentences is option B and stays out of scope.

## Goals

1. **One translation architecture** for the whole console:
   - dictionaries split by area (`core/i18n/en/*.ts`, `core/i18n/fr/*.ts`: shell, overview, alerts, wallet, …, enums,
     errors, landing), merged into the existing `Dictionary` type; each French area file is typed against its English one,
     so a missing key fails compilation;
   - plurals and choices written inside the entry with a small ICU subset (`{count, plural, one {# alerte} other {# alertes}}`,
     `{kind, select, address {…} other {…}}`), formatted by the existing `t()`; numbers inside use the language's format;
   - the per-file tables of PR #37 (levels, severities, statuses, risk levels, signal types, label categories, shipped rule
     names) move into an `enums` area; one way to translate, one place to look.
2. **Every screen translated**: alerts, wallet, risk, explorer, fund flow graph, traces, investigations, predictions,
   community labels, rule builder, rules as code, rule simulator, watchlist, integrations, settings, landing, plus every
   shared component and dialog, toasts, empty/error/loading states, `aria-label`s, `title`s and placeholders.
3. **Server codes** (additive, English kept for other API clients):
   - problem+json gains `code` (stable, e.g. `network.not_enabled`) and `params` (object); the console shows its
     translation, else the server's `detail`;
   - field errors (`errors[]`) and rule validation errors (oci-alerting `ValidationError`, 16 codes, shown by the rule
     editor) gain `params`; a code that today covers different messages is split so that one code = one message template
     (e.g. `required` → `field.required` with `{field}`, `{max}`); field and validation codes are in the same catalogue;
   - `networks[].limitations` keeps its English strings and gains `limitationCodes: [{ code, params }]`;
   - `unreachableReasons` of rules keeps its strings and gains `unreachableCodes: [{ code, params }]`;
   - every code is declared once in Java (`ApiCodes` catalogue); a test writes the catalogue to `docs/api/codes.json`, and a
     frontend test fails if a code in that file has no English and French entry.
4. **Rule texts**: rule schema `openchain.io/rule/v1` gains an optional `translations` map (`fr: { name, description,
   documentation }`), additive; the 10 shipped rules get their French. **The rule YAML is the only source** of a rule's
   French texts (the console's `enum.rule.*` names of PR #37 are removed): `RuleDto` returns `translations`, and
   `AlertDto` gains `ruleTranslations` (the current version's `translations`, joined by rule id on the server), so alert
   lists, alert detail and the palette show the translated name without needing `rules:read`. Fallback: the rule's own
   text. Adding the block changes each shipped rule file: each gets a patch version bump (e.g. 1.0.0 → 1.0.1, noted
   "translations only") and therefore one new stored version in production, with no change to detection.
5. **Guard against regressions**: a frontend test scans every component template and inline template for visible text,
   `aria-label`, `title` and `placeholder` literals containing letters that do not go through `t()`; an explicit
   allowlist covers technical identifiers. The allowlist of not-yet-translated screens starts with all of them and must be
   empty at the end of this work.

## Non-goals

- Rewriting how the engine stores sentences (option B); translating stored summaries, evidence descriptions, alert
  history, comments written by users.
- Translating the Telegram notifications (already French), logs, the YAML/JSON syntax, rule field paths, API docs, the
  README and guides.
- A third language, right-to-left, per-user language stored on the server.

## Anti-goals

- A French text that says more than the English one or than the evidence (ADR-0023); hedging and levels of proof kept.
- An accusatory word in French that the English avoids (the rules repository test bans "criminals, scammers, thieves…";
  its French counterpart bans "criminel, escroc, voleur, fraudeur, coupable, illégal…").
- A raw key, an empty string or an unfilled `{param}` on screen.
- Breaking an API client: every server change is additive.

## Constraints

- Angular 22, signals, OnPush, Vitest; one image per deploy; no new runtime dependency.
- Dictionary size stays modest (EN+FR ≈ 2,600 entries): loaded with the app, no lazy loading needed; checked against the
  existing bundle budget.
- Light/dark themes, phone width, keyboard and screen-reader access unchanged; French strings are longer (≈ +20 %):
  layouts must not overflow at 375 px.
- Order of work keeps the console shippable after each step (each screen is done when its allowlist entry is removed).

## Approaches considered

See "Decisions" above: architecture 1 (chosen), 2 (server answers in the requested language), 3 (Transloco + server
messages). Within architecture 1, plurals: functions in the dictionary (PR #37 style) vs an ICU subset; the ICU subset is
chosen (Claude's call) because translators read it without code, and one formatter covers plural, select and numbers.

## Design

### Frontend

- `core/i18n/en/<area>.ts` exports `const <area> = { … } as const satisfies Record<string, Entry>`; `en/index.ts` spreads
  all areas into `en`; same for `fr`, each French area typed `Record<keyof typeof en<Area>, Entry>`. Keys keep the
  `<area>.<name>` prefix (unique across areas; a test checks no key is defined twice).
- `Entry` = string (with `{param}` and the ICU subset) or, rarely, a function for grammar the subset cannot express.
- `t(key, params)` gains the ICU subset: `plural` (categories from `Intl.PluralRules`, `#` = the number formatted for the
  language, `=0` exact matches), `select`; nested once.
- `enums` area: `enum.level.*`, `enum.severity.*`, `enum.status.*`, `enum.risk.*`, `enum.signal.*`, `enum.category.*`,
  `enum.rule.<id>.name`; existing helpers (`levelWord`, `severityName`, `signalTypeName`, `categoryName`, `ruleDisplayName`,
  `RiskBadge`) read them.
- `errors` area: one entry per server code; `api-error.ts` maps a problem+json to `t('error.' + code, params)` when known.
- Formatting: every remaining `toLocaleString('en-US')`, `timeAgo`, `formatAge` and `sentence()` used for display goes
  through `core/i18n/format.ts`.
- The template scanner test (`i18n-coverage.spec.ts`) reads component sources from disk at test time.

### Server

- `ApiException` gains `code` and `params`; factory methods take a code; the problem+json writer adds both. Existing
  `type`, `title`, `detail`, `errors` unchanged.
- `ApiCodes`: one class listing every code with its English template; `ApiCodesTest` writes `docs/api/codes.json` and
  fails if the committed file differs (so the frontend test always sees the current list).
- `NetworkDto.limitationCodes`, `RuleDto.unreachableCodes`, `RuleDto.translations` (additive).
- Rule schema: `translations` optional object keyed by language (`fr` only for now), each with optional `name`,
  `description`, `documentation`; validated by `SchemaConsistencyTest`; the rules repository test requires `fr` on every
  shipped rule and applies the French word ban.

### Order of work

1. Architecture: area files, ICU subset, enum tables moved, scanner test with every screen allowlisted.
2. Server codes and rule translations (with the 10 shipped rules in French).
3. Screens, one per step, removing its allowlist entry: shared components and dialogs first, then alerts, wallet, risk,
   explorer, investigation graph, traces, investigations, predictions, community, rule builder, rule code, simulator,
   watchlist, integrations, settings, landing.
4. Fixing the deferred minors of PR #37 that this touches (plurals for 1, "Retry", environment popover, switch
   `aria-label`s, no-break space before "?" and ":" in French).

## Delivery (Claude's call)

Four pull requests, each green and mergeable on its own: (1) architecture + scanner; (2) server codes, rule
translations, `AlertDto.ruleTranslations`; (3) shared components, alerts, wallet, risk, explorer, graph, traces,
investigations, predictions; (4) community, rule builder, rules as code, simulator, watchlist, integrations, settings,
landing, PR #37 minors. Server changes are additive, so the order is safe. One production deploy at the end, after the
maintainer's go (backup, rollback tags, as usual); an earlier deploy only if the maintainer asks.

## Claude's calls (stated, correctable)

- Engine-composed sentences stay English, labelled and marked `lang="en"` (section "Engine sentences").
- Field and validation errors translated by code with parameters; ambiguous codes split.
- Rule YAML is the single source of rule French; alerts carry the translations from the server.
- Shipped rules get a patch version bump for their translations.
- Four PRs, one deploy at the end.

- ICU subset rather than functions for plurals; area files rather than one big file.
- Codes in problem+json and DTOs, English kept beside them.
- French rule texts inside each rule YAML (`translations.fr`).
- The landing page is in scope (it is a console screen shown to prospects).
- French typography: no-break space before `? ! : ;` and inside « ».

## Left to the builder

- Exact key names within an area; how the scanner recognises a translated binding; the allowlist format.
- Which shared helpers to delete once their callers use the dictionaries.
- Wording of each French text, within the anti-goals above.
