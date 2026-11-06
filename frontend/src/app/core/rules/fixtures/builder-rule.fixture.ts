import { RuleDraft, emptyRule, newCondition, newGroup } from '../rule-model';

/**
 * Contract fixture shared with the backend: rule-yaml.spec.ts asserts that the builder serializer emits exactly
 * BUILDER_RULE_YAML for builderFixtureDraft(), and oci-app FrontendRuleContractTest reads the text between the
 * BEGIN/END markers and checks that POST /api/v1/rules/validate accepts it (200) and that it can be created + enabled.
 * Keep both in sync: regenerate the YAML from the spec when the serializer changes.
 */
export function builderFixtureDraft(): RuleDraft {
  return {
    ...emptyRule(),
    id: 'builder-large-usdt-outflow',
    name: 'Large USDT outflow: from the builder',
    description: 'Flags transfers above 100k USDT # not a comment',
    author: 'console',
    severity: 'HIGH',
    networks: ['tron', 'ethereum'],
    eventTypes: ['TOKEN_TRANSFER'],
    when: newGroup('all', [
      newCondition('transaction.token.symbol', 'eq', 'USDT'),
      newCondition('transaction.amount', 'gte', '100000.000001'),
      newGroup('any', [
        newCondition('counterparty.risk_score', 'gt', 70),
        newCondition('counterparty.labels', 'contains', 'mixer'),
        newCondition('network', 'in', ['tron', 'bsc']),
      ]),
      newGroup('not', [newCondition('transaction.success', 'eq', false)]),
    ]),
    window: { duration: '1h', aggregate: 'sum', field: 'transaction.amount', groupBy: ['transaction.from'], op: 'gt', value: '123456789012345678901234.5' },
    actions: [{ type: 'alert', target: '' }, { type: 'webhook', target: 'default-webhook' }],
    cooldown: '30m',
  };
}

// BEGIN BUILDER_RULE_YAML
export const BUILDER_RULE_YAML = `apiVersion: openchain.io/rule/v1
id: builder-large-usdt-outflow
version: 1.0.0
name: "Large USDT outflow: from the builder"
description: "Flags transfers above 100k USDT # not a comment"
author: console
enabled: true
scope:
  networks: [tron, ethereum]
  eventTypes: [TOKEN_TRANSFER]
conditions:
  logic: AND
  rules:
    - field: transaction.token.symbol
      operator: EQUALS
      value: USDT
    - field: transaction.amount
      operator: GREATER_THAN_OR_EQUAL
      value: "100000.000001"
  groups:
    - logic: OR
      rules:
        - field: counterparty.risk_score
          operator: GREATER_THAN
          value: 70
        - field: counterparty.labels
          operator: CONTAINS
          value: mixer
        - field: network
          operator: IN
          value: [tron, bsc]
    - logic: NOT
      rules:
        - field: transaction.success
          operator: EQUALS
          value: false
aggregation:
  window: 1h
  groupBy: [transaction.from]
  function: SUM
  field: transaction.amount
  operator: GREATER_THAN
  threshold: "123456789012345678901234.5"
severity: HIGH
evidenceLevel: ANOMALY
cooldown: 30m
actions:
  - type: WEBHOOK
    connectorId: default-webhook
`;
// END BUILDER_RULE_YAML
