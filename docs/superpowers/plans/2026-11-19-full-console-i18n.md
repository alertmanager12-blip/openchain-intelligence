# Whole console in English and French Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every console text exists in English and French, on one typed architecture, with the server sending codes instead of English for its fixed texts.

**Architecture:** Area dictionaries (`core/i18n/en|fr/<area>.ts`) merged into the PR #37 `Dictionary` type, an ICU subset formatter inside `t()`, enum tables moved into an `enums` area, a source scanner test that refuses hard-coded English. The server adds `code`/`params` to problem+json, field and rule-validation errors, `limitationCodes`, `unreachableCodes`, and rule `translations` (rule YAML is the only source of rule French; alerts carry them).

**Tech Stack:** Angular 22 (signals, OnPush, Vitest), Spring Boot 4 / Java 21, rule schema `openchain.io/rule/v1`.

**Spec:** `docs/superpowers/specs/2026-11-19-full-console-i18n-design.md`

## Global Constraints

- Option C: engine-composed sentences (summaries, evidence descriptions, `methodSummary`, simulator/prediction explanations, `ignoredReason`) stay English, inside `lang="en"`, introduced by "Formulation du moteur :" / "Engine's wording:"; their structured names are translated.
- Every server change is additive: existing `type`, `title`, `detail`, `errors[].message`, `limitations`, `unreachableReasons`, `ruleName` keep their English.
- No new runtime dependency; no lazy loading of dictionaries.
- A missing French key fails compilation; a duplicate key across areas fails a test; a server code without EN+FR entry fails a test.
- French typography: narrow no-break space (U+202F) before `? ! ; :` and inside « »; a test checks every French entry.
- French word ban (rules repository test): criminel, escroc, voleur, fraudeur, coupable, illégal, terroriste, blanchisseur (and plurals/feminines).
- Shipped rules: patch version bump with the `translations.fr` block ("translations only"); detection unchanged.
- Delivery: PR A = Tasks 1–4, PR B = Tasks 5–9, PR C = Tasks 10–14, PR D = Tasks 15–20; each green on its own; one production deploy at the end after the maintainer's go.
- Commits: French subjects in the repo style, no Claude attribution lines.
- Tests: frontend `cd frontend && npx ng test --watch=false [--include=<glob>]`; backend `mvn -q -pl <module> -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`. If oci-app reports "Unresolved compilation problems" for test support classes, `touch` the reported source and rerun (IDE writing into target/).

## Review Focus

1. A newer server sends a code the console does not know: the console shows the server's English `detail`, never a raw key → test in Task 9.
2. A rule carries `translations.fr.name` only: description and documentation fall back to the rule's own English, never empty → test in Task 8.
3. The language is switched while a dialog, drawer or the alert pane is open: its texts re-render without closing it → test in Task 10.
4. French texts at 375 px on the densest screens (alerts list, rule builder, wallet): no horizontal overflow → check in Tasks 11, 12, 16 (headless Chrome with CDP device emulation, `scrollWidth === 375`).
5. ICU plurals at 0, 1, 2 and a missing parameter: French "0 alerte", "1 alerte", "2 alertes"; a missing param leaves `{name}` visible in tests only, never throws → test in Task 1.

---

## PR A — architecture

### Task 1: ICU subset in `t()`

**Files:**
- Create: `frontend/src/app/core/i18n/icu.ts`, `frontend/src/app/core/i18n/icu.spec.ts`
- Modify: `frontend/src/app/core/i18n/i18n.service.ts`

**Interfaces:**
- Produces: `formatIcu(message: string, params: Params, lang: Lang): string` — `{name}` replaced (numbers via `fmtNumber`); `{n, plural, =0 {…} one {…} other {…}}` with `#` = `fmtNumber(n)`, categories from `Intl.PluralRules`, exact `=N` first; `{k, select, a {…} other {…}}`; one level of nesting; unknown param leaves `{name}`; never throws.
- Produces: `translate(lang: Lang, key: Key, params?: Params): string` (pure, exported from `i18n.service.ts`); `I18nService.t(key, params)` = `translate(this.lang(), key, params)`.

- [ ] **Step 1: Failing tests** `icu.spec.ts`: `formatIcu('{n, plural, one {# alerte} other {# alertes}}', {n:0}, 'fr')` → `'0 alerte'`; n=1 → `'1 alerte'`; n=1385 → `'1 385 alertes'`; same message `'en'` n=0 → `'0 alertes'` (English 0 is `other`); `=0 {aucune}` wins over `one`; `{k, select, address {adresse} other {transaction}}` with k='tx' → `'transaction'`; nested `{n, plural, one {{who} a gelé # adresse} other {{who} a gelé # adresses}}`; `'Bonjour {name}'` without name → `'Bonjour {name}'`; malformed `'{n, plural, one {x'` → returned unchanged.
- [ ] **Step 2: Run, expect FAIL** (module missing).
- [ ] **Step 3: Implement** a small recursive-descent parser (braces balanced, no external lib); `translate` uses it for string entries, functions entries unchanged.
- [ ] **Step 4: Run** icu + i18n specs → PASS; full suite → PASS.
- [ ] **Step 5: Commit** `feat(console): pluriels et choix dans les traductions (sous-ensemble ICU)`.

### Task 2: Dictionaries split by area

**Files:**
- Create: `frontend/src/app/core/i18n/en/{shell,overview,band,verdict,enums,errors,index}.ts`, same under `fr/`; `frontend/src/app/core/i18n/dictionaries.spec.ts`
- Delete: `frontend/src/app/core/i18n/en.ts`, `fr.ts` (content moved)
- Modify: imports of `en`/`fr` (`i18n.service.ts`, its spec)

**Interfaces:**
- Produces: `en/<area>.ts` → `export const <area> = {…} as const satisfies Record<string, Entry>`; `fr/<area>.ts` → `export const <area>: Record<keyof typeof en<Area>, Entry>` (import the English area type); `en/index.ts` and `fr/index.ts` export `en` / `fr` as spreads of their areas; `Key`, `Dictionary` unchanged in meaning.
- Every key keeps the `<area>.` prefix (`enum.` for enums, `error.` for errors).

- [ ] **Step 1: Failing test** `dictionaries.spec.ts`: no key defined in two areas (collect keys per area file, assert sum of sizes = size of `en`); every key of each English area starts with that area's prefix; every French entry: no ASCII space before `?`, `!`, `;`, `:` (use U+202F), no `"` (use « »).
- [ ] **Step 2: Run, expect FAIL** (files missing).
- [ ] **Step 3: Move** every PR #37 entry into its area; rewrite `overview.allOpen`, `overview.alertsUnit`, `overview.net.blocks`, `shell.countOpenAlerts`, `band.passed`, `band.olderGaps` with ICU plurals; fix French typography found by the test (e.g. `band.title` → `'Ma surveillance est-elle fiable ?'`).
- [ ] **Step 4: Run** full suite → PASS (existing French expectations updated only where typography changed).
- [ ] **Step 5: Commit** `refactor(console): dictionnaires par domaine, pluriels corrigés`.

### Task 3: Enum tables into the `enums` area

**Files:**
- Modify: `frontend/src/app/shared/levels.ts`, `shared/format.ts` (`severityName`, `statusName`, `sentence` callers kept), `shared/explain.ts` (`SIGNAL_TITLES*`, `CATEGORIES*`, `LABEL_CATEGORIES`), `shared/ui.ts` (`RISK_NAMES`), `shared/alert-sentence.ts` (`NAMES` stays until Task 9), `core/i18n/en|fr/enums.ts`
- Test: existing specs of those files

**Interfaces:**
- Produces keys: `enum.level.<LEVEL>`, `enum.evidence.<EVIDENCE_LEVEL>`, `enum.severity.<S>`, `enum.status.<S>`, `enum.risk.<R>`, `enum.signal.<TYPE>`, `enum.category.<CATEGORY>` (category text reads after "listed as" / "listée comme"), `enum.factor.<FACTOR>` (risk contribution factors, from `shared/explain.ts` `factorTitle`).
- Helpers keep their signatures and read `translate(lang, …)`; unknown enum value → existing fallback (`sentence(value)` / `null` for `signalTypeName`).

- [ ] **Step 1: Failing test** (in `dictionaries.spec.ts`): for each enum in `core/models.ts` (`SEVERITIES`, `ALERT_STATUSES`, risk levels, evidence levels) every value has an `enum.*` key; `levels.ts`, `explain.ts`, `ui.ts` contain no French literal (read sources, assert no `/[éèàù]/`).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Move** the tables; delete the per-file tables.
- [ ] **Step 4: Run** full suite → PASS (all existing helper tests unchanged).
- [ ] **Step 5: Commit** `refactor(console): noms d'énumérations dans les dictionnaires`.

### Task 4: Hard-coded English scanner, engine-text component, PR #37 minors

**Files:**
- Create: `frontend/src/app/core/i18n/i18n-coverage.spec.ts`, `frontend/src/app/core/i18n/coverage-allowlist.ts`, `frontend/src/app/shared/engine-text.ts`, `shared/engine-text.spec.ts`
- Modify: `shared/ui.ts` (state "Retry"), `app.ts` (environment popover), `layout/shell.ts` (env labels), `layout/lang-switch.ts` (aria-labels), `shared/evidence-line.ts` (use `oci-engine-text` for `original`)

**Interfaces:**
- Produces: `COVERAGE_ALLOWLIST: { files: string[]; tokens: RegExp[] }` — `files` = component sources not yet translated (relative to `src/app`), `tokens` = identifiers allowed literally (`/^[A-Z0-9_.:-]+$/`, network and token symbols, `ChainSentinel`, `OFAC SDN`, `TRON`, `Ethereum`, `YAML`, `JSON`, `API`, `OIDC`, `USDT`, `USDC`, `ETH`, `TRX`, keyboard keys).
- Scanner rule (in the spec): for every `*.ts` with `template:` and every `*.html` under `src/app` (excluding `*.spec.ts`, `core/i18n/**`, `core/mock/**`): text between tags after removing `{{…}}`, `@…{`, `}` and entities, plus literal values of `aria-label`, `title`, `placeholder`, `alt`, `matTooltip` attributes; a chunk containing a word of ≥ 3 letters not matching `tokens` is a finding. Fails on findings in files not in `files`; also fails if a file in `files` has no finding (allowlist must shrink).
- Produces: `<oci-engine-text [text]="string" [mode]="'visible' | 'collapsed'" />` — label `t('engine.label')` ("Engine's wording:" / "Formulation du moteur :"), text in `<span lang="en">`; `collapsed` = `<details>` with the label as summary.

- [ ] **Step 1: Failing tests:** scanner spec with the allowlist initially listing every untranslated file found (generate the list once by running the scanner, then commit it); `engine-text.spec.ts`: French label, `lang="en"` on the text, collapsed renders `details`; `shell.spec` French: env popover text French, state "Réessayer"; `lang-switch.spec`: buttons carry `aria-label` "Français" / "English".
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement**; the allowlist contains exactly the files the scanner reports after this task's own translations.
- [ ] **Step 4: Run** full suite + `npm run build -- --configuration production` → PASS.
- [ ] **Step 5: Commit** `feat(console): garde-fou contre l'anglais en dur et formulation du moteur étiquetée`. **End of PR A** (push, PR, CI green; merge only with the maintainer's go).

## PR B — server codes and rule translations

### Task 5: Codes in problem+json and field errors

**Files:**
- Create: `oci-app/src/main/java/io/openchain/app/api/ApiCode.java`, `oci-app/src/test/java/io/openchain/app/api/ApiCodeTest.java`, `docs/api/codes.json`
- Modify: `ApiException.java`, `Problems.java`, `GlobalExceptionHandler.java`, every `oci-app` throw site (15 files: `git grep -l "ApiException\.\|new ApiException(" -- oci-app/src/main/java`)

**Interfaces:**
- Produces: `enum ApiCode { …; String id(); String template(); }` — `id` like `network.not_enabled`, `template` = English with `{param}` names; one constant per distinct message (split today's shared messages).
- `ApiException(int status, String type, String title, ApiCode code, Map<String, Object> params, List<FieldError> errors)`; `detail` = `code.template()` with params filled (English, unchanged wording where it existed); factories `badRequest(ApiCode, Map)`, `notFound(ApiCode, Map)`, `conflict(ApiCode, Map)`, `validation(ApiCode, Map, List<FieldError>)`, `upstream(ApiCode, Map)`, `RateLimited(ApiCode, Map, long)`; old `String` factories removed once no caller remains.
- `FieldError(String pointer, String code, Map<String, Object> params, String message)` — `code` is an `ApiCode.id()` of kind `field.*` (e.g. `field.required` with `{field}`, `field.too_long` with `{field}`, `{max}`); `toMap()` adds `params`.
- problem+json gains `"code"` and `"params"`; `errors[]` items gain `"params"`.
- `docs/api/codes.json`: sorted array of `{ "code": id, "template": template }` for every `ApiCode` and every rule-validation key (Task 6).

- [ ] **Step 1: Failing tests:** `ApiCodeTest.everyCodeIsListedInDocsApiCodesJson` (generate expected JSON from the enum, compare with the committed file; on mismatch print the expected content); `ApiCodeTest.idsAreUniqueAndTemplatesUseOnlyDeclaredParams`; an API test per status family asserting `$.code` and `$.params` (e.g. wallet on a disabled network → `network.not_enabled`, `params.network = "bsc"`; missing key name → `errors[0].code = "field.required"`, `errors[0].params.field = "name"`), existing `$.detail` assertions unchanged.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** and convert every throw site (keep each English detail word for word in the template).
- [ ] **Step 4: Run** `mvn -q -pl oci-app -am test` → PASS.
- [ ] **Step 5: Commit** `feat(api): codes et paramètres dans les erreurs (problem+json, erreurs de champ)`.

### Task 6: Codes for rule validation errors

**Files:**
- Modify: `oci-alerting/src/main/java/io/openchain/alerting/rule/ValidationError.java`, `RuleValidator.java` (98 sites), `RuleParser.java`, the oci-app mapping of `ValidationError` to `FieldError`, `ApiCodeTest`, `docs/api/codes.json`
- Test: `oci-alerting/src/test/java/io/openchain/alerting/rule/RuleParserValidatorTest.java`

**Interfaces:**
- Produces: `ValidationError(String path, Code code, String key, Map<String, Object> params, String message)` with a 3-arg constructor kept for callers outside the validator; `key` = `rule.<snake_name>` unique per message template (e.g. `rule.unknown_field` `{field}`, `rule.operator_not_allowed` `{operator}`, `{type}`); `message` = English template filled (unchanged wording).
- `RuleMessages` (oci-alerting): `static Map<String, String> templates()` listing every key → English template; `ApiCodeTest` merges it into `codes.json`.
- Mapped `FieldError.code` = `key`, `params` = `params`.

- [ ] **Step 1: Failing tests:** `RuleParserValidatorTest.everyErrorCarriesAKeyAndParamsThatFillItsTemplate` — for a corpus of invalid rules (reuse the test's existing invalid fixtures), each error's `key` is in `RuleMessages.templates()` and filling the template with `params` equals `message`; API test: invalid rule → `errors[0].code` starts with `rule.`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** (one key per distinct message template).
- [ ] **Step 4: Run** `mvn -q -pl oci-alerting,oci-app -am test` → PASS.
- [ ] **Step 5: Commit** `feat(rules): codes et paramètres pour chaque erreur de validation de règle`.

### Task 7: Limitation and unreachability codes

**Files:**
- Modify: `oci-app/.../analysis/InvestigationService.java` (`limitations`), `api/Dto.java` (`NetworkDto`, `RuleDto`, wallet/investigation DTOs carrying limitations), `rules/RuleRegistry.java` (`unreachable`), `ApiCode.java`, `docs/api/codes.json`
- Test: `DashboardAndRoutingApiTest`, the rules API test

**Interfaces:**
- Produces: `record Coded(String code, Map<String, Object> params)`; `NetworkDto.limitationCodes: List<Coded>` (same order as `limitations`), `RuleDto.unreachableCodes: List<Coded>`; codes `limitation.age_warmup` (`{need}`, `{seen}`), `limitation.tron_no_balance`, …, `unreachable.field_not_produced` (`{field}`), `unreachable.event_type_not_produced` (`{type}`).

- [ ] **Step 1: Failing tests:** networks API: `limitationCodes` present with the same length as `limitations`; rules API: a rule using an unpopulated field has `unreachableCodes[0].code = "unreachable.field_not_produced"` with `params.field`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `mvn -q -pl oci-app -am test` → PASS.
- [ ] **Step 5: Commit** `feat(api): codes pour les limites des réseaux et les règles injoignables`.

### Task 8: Rule translations (schema, shipped rules, DTOs)

**Files:**
- Modify: `oci-alerting/src/main/resources/schema/rule-v1.schema.json`, `oci-alerting/.../rule/Rule.java` (new component `Map<String, RuleTranslation> translations`, last), `RuleValidator.java:141`, `oci-app/.../rules/RuleRegistry.java:376`, `api/Dto.java` (`RuleDto.translations`, `AlertDto.ruleTranslations`), `alerts/AlertFacade.java`, every `rules/**/*.yaml` (10 files: `translations.fr` + patch version bump), `oci-alerting/src/test/.../RulesRepositoryTest.java`
- Create: `oci-alerting/.../rule/RuleTranslation.java`

**Interfaces:**
- Produces: `record RuleTranslation(String name, String description, String documentation)` (each nullable); YAML `translations: { fr: { name, description, documentation } }`; validation: keys are `fr` (only language accepted for now, else `rule.translation_language`), strings within the same length limits as the originals.
- `RuleDto.translations: Map<String, RuleTranslation>`; `AlertDto.ruleTranslations: Map<String, RuleTranslation>` = current version's translations of `ruleId` (empty when the rule is gone).
- Translations do not change detection: a test asserts the compiled conditions of a rule with and without `translations` are equal.

- [ ] **Step 1: Failing tests:** `RulesRepositoryTest.everyShippedRuleHasAFrenchNameDescriptionAndDocumentation`; `RulesRepositoryTest.frenchTextsAvoidAccusatoryWords` (ban list of Global Constraints); `RuleParserValidatorTest.translationsAreOptionalAndValidated` (no block OK; `de:` → `rule.translation_language`); compiled-equality test; API: `GET /rules/wallet-draining` → `translations.fr.name`; `GET /alerts?limit=1` → `items[0].ruleTranslations.fr.name`; a rule with only `fr.name` → `description` absent in `translations.fr` (Review Focus 2, console side in Task 9).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement**; write the French texts of the 10 shipped rules (same facts and hedging as English; documentation translated in full); bump each `version` patch.
- [ ] **Step 4: Run** `mvn -q -pl oci-alerting,oci-app -am test` → PASS.
- [ ] **Step 5: Commit** `feat(rules): traductions françaises des règles fournies (schéma v1, additif)`.

### Task 9: Console reads codes and rule translations

**Files:**
- Modify: `frontend/src/app/core/api-error.ts`, `core/models.ts` (`ApiError.code/params`, `FieldError.code/params`, `NetworkStatus.limitationCodes`, `Rule.translations`, `Alert.ruleTranslations`, `unreachableCodes`), `core/i18n/en|fr/errors.ts`, `shared/alert-sentence.ts` (`ruleDisplayName` → reads translations; delete `NAMES`), callers of `ruleName` (alerts list, alert detail, palette, overview 24 h)
- Create: `frontend/src/app/core/i18n/codes-coverage.spec.ts`, `frontend/src/app/shared/rule-text.ts`

**Interfaces:**
- Produces: `errorText(e: ApiError, lang: Lang): string` (code known → `translate(lang, 'error.'+code, params)`, else `detail ?? title`); `fieldErrorText(f: FieldError, lang)`; `ruleText(r: { name: string; description?: string | null; documentation?: string | null; translations?: Record<string, Partial<RuleTranslation>> }, lang: Lang): { name: string; description: string | null; documentation: string | null }` (per-field fallback to the rule's own text); `ruleName(a: Alert, lang: Lang): string`.
- `codes-coverage.spec.ts` reads `../../../../docs/api/codes.json` (path from the spec file) and asserts every code has `error.<code>` in EN and FR.

- [ ] **Step 1: Failing tests:** codes coverage; `errorText` with an unknown code → `detail` (Review Focus 1); `ruleText` with only `fr.name` → French name, English description (Review Focus 2); alerts list in French shows "Portefeuille vidé" from `ruleTranslations`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement**; write `error.*` entries for every code in `codes.json`.
- [ ] **Step 4: Run** full frontend suite → PASS.
- [ ] **Step 5: Commit** `feat(console): erreurs, limites et règles traduites depuis les codes du serveur`. **End of PR B.**

## PR C and PR D — screens

Every screen task follows the same recipe:

1. **Failing test**: a French render spec for the screen (`<screen>.i18n.spec.ts`, mock backend, `localStorage['oci.lang'] = 'fr'`) asserting 3–5 visible French headings/labels named in the task, `lang="en"` around engine sentences named in the task, and an English re-render after `I18nService.set('en')` without navigation; plus removing the screen's files from `COVERAGE_ALLOWLIST.files` (the scanner then fails).
2. **Run, expect FAIL.**
3. **Translate**: move every literal (text, `aria-label`, `title`, `placeholder`, `matTooltip`, toast and dialog texts, `confirm` messages, empty/error/loading states) to `core/i18n/en|fr/<area>.ts`; numbers, dates, durations through `core/i18n/format.ts`; enums through `enum.*`; engine sentences through `<oci-engine-text>`; rule names through `ruleName`/`ruleText`; errors through `errorText`.
4. **Run** the screen spec, the scanner and the full suite → PASS; production build OK.
5. **Commit** `feat(console): <écran> en français et en anglais`.

| Task | Files (under `frontend/src/app`) | French assertions (examples) | Engine sentences shown via `oci-engine-text` |
|---|---|---|---|
| 10 | `shared/**` (dialogs, toasts, state, table-state, page-header leftovers, risk-gauge, explain, network-not-enabled, confirm-dialog), `layout/shortcut-help.ts` descriptions | confirm dialog "Annuler"/"Confirmer"; table empty state; shortcut "Alerte suivante"; dialog stays open across a language switch (Review Focus 3) | — |
| 11 | `features/alerts/**` | "Alertes", "Prendre en charge", "Faux positif", filters, status names | `evidenceSummary`, evidence descriptions, `ignoredReason`; overflow check at 375 px (Review Focus 4) |
| 12 | `features/wallet/**`, `features/risk/**` | "Score de risque", "Contreparties", "Non visible sur ce réseau" (limitation from `limitationCodes`) | `methodSummary` (visible), contribution summaries, evidence descriptions; 375 px check |
| 13 | `features/explorer/**`, `features/investigation/**`, `features/traces/**` | "Rechercher une adresse", graph legend, "Traçages enregistrés" | trace explanations |
| 14 | `features/investigations/**`, `features/predictions/**` | "Enquêtes", assertion states, "Prédictions et scénarios" | prediction and simulation explanations. **End of PR C.** |
| 15 | `features/community/**`, `features/watchlist/**`, `features/integrations/**` | "Étiquettes communautaires", "Liste de surveillance", connector forms | — |
| 16 | `features/rule-builder/**`, `features/rule-code/**`, `features/simulator/**`, `core/rules/**` (field descriptions of `rule-model.ts` translated; field paths stay) | "Créateur de règles", operator names, validation errors via `fieldErrorText`, rule name/description via `ruleText` | simulator match summaries; 375 px check |
| 17 | `features/settings/**` | "Réglages", "Connexion", "Clés API", "Langue" (a language setting mirrors the switch), sanctions card | — |
| 18 | `features/landing/**` | hero, capabilities, how-it-works, developers, closing in French (same claims as English, no new claim) | — |

### Task 19: Allowlist empty

**Files:** `frontend/src/app/core/i18n/coverage-allowlist.ts`, `i18n-coverage.spec.ts`

- [ ] **Step 1: Failing test:** `i18n-coverage.spec` asserts `COVERAGE_ALLOWLIST.files` is empty.
- [ ] **Step 2: Run** → PASS only if Tasks 10–18 removed every entry; translate any leftover file.
- [ ] **Step 3: Commit** `test(console): plus aucun écran en anglais en dur`.

### Task 20: Docs and whole-branch checks

**Files:** `CHANGELOG.md`, `docs/api/README.md` (problem+json `code`/`params`, `limitationCodes`, `unreachableCodes`, `translations`, `ruleTranslations`), `docs/guides/writing-rules.md` (`translations` block)

- [ ] **Step 1:** `mvn -q -pl oci-app -am test`, frontend full suite, production build → PASS.
- [ ] **Step 2:** Update the docs.
- [ ] **Step 3: Commit** `docs: console entière en anglais et en français`. **End of PR D.**
