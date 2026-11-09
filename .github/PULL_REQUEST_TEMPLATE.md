<!--
Thanks for contributing! Fill in what applies and delete what does not.
Security vulnerabilities that are not public yet must not be fixed in a public pull request: see SECURITY.md.
-->

## Summary

<!-- What does this change, in a few sentences? -->

## Problem solved

<!-- Why is it needed? Link the issue: "Fixes #123" or "Refs #123". -->

## Technical approach

<!-- How does it work? Alternatives you rejected and why. -->

## Modules affected

- [ ] `oci-core`
- [ ] `oci-blockchain`
- [ ] `oci-intelligence`
- [ ] `oci-alerting`
- [ ] `oci-app`
- [ ] `frontend`
- [ ] `rules`
- [ ] Deployment, CI or documentation only

## Tests

<!-- Tests added or changed, and the commands you ran with their real results, e.g.
`mvn -B clean verify`: BUILD SUCCESS, Tests run: 506, Failures: 0, Skipped: 0
`cd frontend && npm test`: 142 passed -->

## Compatibility

<!-- Does it change the REST API v1, the rule schema, configuration variables, stored data or default behaviour?
If yes: what breaks, and what operators must do. Write "None" otherwise. -->

## Security

<!-- Does it touch authentication, API keys, secrets, connectors or webhooks, the rule engine, input parsing,
the deployment or the CI workflows? What did you check? Write "None" otherwise. -->

## Documentation and migration

<!-- Documentation updated, CHANGELOG entry, Flyway migration, steps to upgrade. -->

## Checklist

- [ ] The code follows the project's conventions and [ground rules](https://github.com/alertmanager12-blip/openchain-intelligence/blob/main/CONTRIBUTING.md#ground-rules) (explainable detection, neutral wording, no user code execution, exact amounts, module boundaries)
- [ ] The necessary tests are present
- [ ] The tests pass (`mvn -B clean verify` with Docker running, and `npm test` for frontend changes)
- [ ] The documentation and `CHANGELOG.md` (`[Unreleased]`) are up to date
- [ ] No secret, API key, token or personal data is added
- [ ] No unrelated change is included
- [ ] Compatibility impacts are documented above
- [ ] New dependencies comply with the licence policy ([ADR-0012](https://github.com/alertmanager12-blip/openchain-intelligence/blob/main/docs/adr/0012-licensing-policy.md))
- [ ] Optional: commits are signed off (`git commit -s`, [DCO](https://github.com/alertmanager12-blip/openchain-intelligence/blob/main/CONTRIBUTING.md#licence-and-sign-off))
