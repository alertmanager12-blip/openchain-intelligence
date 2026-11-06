## Summary

<!-- What does this change and why? Link issues: Closes #123 -->

## Type

- [ ] Bug fix
- [ ] Feature
- [ ] Rule / rule pack
- [ ] Documentation
- [ ] Refactoring / build / CI

## Checklist

- [ ] Commits are signed off (`git commit -s`, DCO)
- [ ] Tests added/updated and `mvn verify` (and `npm test` for frontend) pass locally
- [ ] Module boundaries respected (`oci-blockchain`, `oci-intelligence`, `oci-alerting` depend only on `oci-core`)
- [ ] Amounts use `BigInteger`/`BigDecimal` (no `double` for money)
- [ ] No user-supplied code execution (no SpEL/scripting/reflection on user input)
- [ ] Detection output is explainable and uses neutral wording (no "criminal"/"scam wallet" labels)
- [ ] New dependencies comply with the licence policy (ADR-0012)
- [ ] Docs / CHANGELOG updated (`Unreleased` section)
- [ ] ADR added for significant architectural decisions

## Notes for reviewers

<!-- Risks, migration steps, screenshots for UI changes -->
