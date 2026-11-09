/**
 * Landing page copy (English). Every capability listed here was checked against the code on 2026-11-09; items marked
 * `planned` are on the roadmap (docs/strategy/FEATURE_ROADMAP.md) and are shown as such. A French version can be added as
 * another object of the same type and selected by locale.
 */
export type Availability = 'available' | 'partial' | 'planned';

export interface Capability {
  readonly title: string;
  readonly detail: string;
  readonly status: Availability;
}

export interface Pillar {
  readonly id: string;
  readonly verb: string;
  readonly name: string;
  readonly summary: string;
  readonly items: readonly Capability[];
}

export interface Step {
  readonly name: string;
  readonly summary: string;
  readonly detail: string;
}

export interface UseCase {
  readonly who: string;
  readonly value: string;
}

export const LANDING_COPY = {
  problem: {
    title: "Blockchain transparency doesn’t mean transaction intelligence.",
    lede:
      'Every transfer is public, yet turning raw blocks into a decision still takes hours. Teams face the same obstacles:',
    pains: [
      { title: 'Volume', text: 'Millions of transfers a day, with no context on who sent what, or why.' },
      { title: 'Hidden links', text: 'Wallets act together through splits, relays and round trips that a block explorer does not show.' },
      { title: 'Moving targets', text: 'Behavior changes faster than static blocklists are updated.' },
      { title: 'Scattered signals', text: 'Evidence sits in different tools, chains and spreadsheets.' },
      { title: 'Manual investigations', text: 'Tracing funds hop by hop is slow and hard to reproduce.' },
      { title: 'Alerts without reasons', text: 'A score with no explanation cannot be defended to a customer or an auditor.' },
    ],
    answer:
      'ChainSentinel indexes the chains itself, profiles every wallet it sees, explains each risk signal with the transactions behind it, and turns your rules into alerts.',
  },

  pillars: [
    {
      id: 'understand', verb: 'Understand', name: 'Blockchain intelligence',
      summary: 'Turn indexed transfers into wallet profiles, counterparties and an explainable risk score.',
      items: [
        { title: 'Wallet analysis', detail: 'Activity, tokens, counterparties and first/last seen for any indexed address.', status: 'available' },
        { title: 'Transaction intelligence', detail: 'Per-transaction analysis of the value flows and the parties involved.', status: 'available' },
        { title: 'Behavioral signals', detail: 'Ten detectors, each signal carrying its evidence and evidence level.', status: 'available' },
        { title: 'Risk assessment', detail: 'A 0–100 score with every contribution, its weight and its age, shown in plain language.', status: 'available' },
      ],
    },
    {
      id: 'predict', verb: 'Predict', name: 'Threat detection',
      summary: 'Early-warning signals, not verdicts: each one says what was observed and how strong the evidence is.',
      items: [
        { title: 'Suspicious behavior', detail: 'Structuring, fan-out, fast forwarding and circular flows.', status: 'available' },
        { title: 'Wallet draining', detail: 'Rapid outflows checked against real balances on EVM networks. Not on TRON yet: no historical balance there.', status: 'partial' },
        { title: 'Anomaly detection', detail: 'Unusual amounts, unusual approvals, dormant wallets waking up, contract ownership changes.', status: 'available' },
        { title: 'Predictive scoring', detail: 'Signals that anticipate an incident before funds move, with simulation.', status: 'planned' },
      ],
    },
    {
      id: 'investigate', verb: 'Investigate', name: 'Graph intelligence',
      summary: 'Follow the money across wallets and see who is connected to whom.',
      items: [
        { title: 'Wallet relationships', detail: 'Counterparties and the volumes exchanged with each of them.', status: 'available' },
        { title: 'Investigation graph', detail: 'An interactive fund-flow graph you can expand, filter by token and date, and export.', status: 'available' },
        { title: 'Multi-hop exposure', detail: 'Indirect exposure to labeled wallets, up to three hops away.', status: 'available' },
        { title: 'Path tracing and cases', detail: 'Shortest paths between wallets and reproducible investigation cases.', status: 'planned' },
      ],
    },
    {
      id: 'act', verb: 'Act', name: 'Automated response',
      summary: 'Send the right alert to the right place, and let your own systems take it from there.',
      items: [
        { title: 'Real-time alerts', detail: 'An alert inbox with acknowledgement, assignment, comments and escalation.', status: 'available' },
        { title: 'Notifications', detail: 'Telegram, Slack, Discord and signed webhooks (HMAC-SHA256).', status: 'available' },
        { title: 'Custom detection rules', detail: 'Rules as YAML or from a visual builder, tested on past events before you enable them.', status: 'available' },
        { title: 'API integrations', detail: 'A REST API with scoped API keys or OIDC tokens. Email notifications are planned.', status: 'available' },
      ],
    },
  ] satisfies readonly Pillar[],

  steps: [
    { name: 'Connect', summary: 'Plug in the chains you care about.',
      detail: 'Point ChainSentinel at a TRON or EVM node. It indexes new blocks continuously and resumes where it stopped after a restart.' },
    { name: 'Analyze', summary: 'Every transfer becomes a value flow.',
      detail: 'Tokens and native coins are normalized, wallets are profiled and counterparties linked as blocks arrive.' },
    { name: 'Detect', summary: 'Detectors and your rules run on each flow.',
      detail: 'Signals come with their evidence. Your rules combine them with amounts, labels and the watchlist.' },
    { name: 'Respond', summary: 'Alerts reach your team and your systems.',
      detail: 'An alert lands in the inbox and in Telegram, Slack, Discord or a webhook, with a link back to the wallet to investigate.' },
  ] satisfies readonly Step[],

  useCases: [
    { who: 'Crypto exchanges', value: 'Watch deposit and hot wallets, and get alerted when funds arrive from wallets showing draining or splitting patterns.' },
    { who: 'Fintech and payment platforms', value: 'Screen stablecoin counterparties on TRON and Ethereum before crediting or paying out.' },
    { who: 'Blockchain security teams', value: 'Spot approvals, ownership changes and rapid outflows around the contracts and wallets you protect.' },
    { who: 'Compliance and AML teams', value: 'Document why a wallet was flagged: every score lists its signals, evidence and age. The decision stays with your analysts.' },
    { who: 'Web3 applications', value: 'Query wallet risk from your backend through the API before you let a wallet interact with your product.' },
    { who: 'Blockchain researchers', value: 'Run the detectors on your own infrastructure, read how each one works, and change or extend them.' },
  ] satisfies readonly UseCase[],
} as const;
