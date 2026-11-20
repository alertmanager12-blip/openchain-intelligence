import type { Key } from '../../core/i18n/i18n.service';

/**
 * Landing page structure. The texts live in the dictionaries (core/i18n/en|fr/landing.ts, keys `landing.*`): English and
 * French make the same claims. Every capability listed here was checked against the code on 2026-11-09; items marked
 * `planned` are on the roadmap (docs/strategy/FEATURE_ROADMAP.md) and are shown as such.
 */
export type Availability = 'available' | 'partial' | 'planned';

export interface Capability {
  readonly title: Key;
  readonly detail: Key;
  readonly status: Availability;
}

export interface Pillar {
  readonly id: string;
  readonly verb: Key;
  readonly name: Key;
  readonly summary: Key;
  readonly items: readonly Capability[];
}

export interface Step {
  readonly id: string;
  readonly name: Key;
  readonly summary: Key;
  readonly detail: Key;
}

export interface UseCase {
  readonly who: Key;
  readonly value: Key;
}

export interface Pain {
  readonly title: Key;
  readonly text: Key;
}

export interface LandingCopy {
  readonly problem: { readonly title: Key; readonly lede: Key; readonly answer: Key; readonly pains: readonly Pain[] };
  readonly pillars: readonly Pillar[];
  readonly steps: readonly Step[];
  readonly useCases: readonly UseCase[];
}

export const LANDING_COPY: LandingCopy = {
  problem: {
    title: 'landing.problem.title',
    lede: 'landing.problem.lede',
    pains: [
      { title: 'landing.pain.volume.t', text: 'landing.pain.volume.d' },
      { title: 'landing.pain.hidden.t', text: 'landing.pain.hidden.d' },
      { title: 'landing.pain.moving.t', text: 'landing.pain.moving.d' },
      { title: 'landing.pain.scattered.t', text: 'landing.pain.scattered.d' },
      { title: 'landing.pain.manual.t', text: 'landing.pain.manual.d' },
      { title: 'landing.pain.reasons.t', text: 'landing.pain.reasons.d' },
    ],
    answer: 'landing.problem.answer',
  },

  pillars: [
    {
      id: 'understand', verb: 'landing.pillar.understand.verb', name: 'landing.pillar.understand.name', summary: 'landing.pillar.understand.summary',
      items: [
        { title: 'landing.item.wallet.t', detail: 'landing.item.wallet.d', status: 'available' },
        { title: 'landing.item.tx.t', detail: 'landing.item.tx.d', status: 'available' },
        { title: 'landing.item.behavior.t', detail: 'landing.item.behavior.d', status: 'available' },
        { title: 'landing.item.risk.t', detail: 'landing.item.risk.d', status: 'available' },
      ],
    },
    {
      id: 'predict', verb: 'landing.pillar.predict.verb', name: 'landing.pillar.predict.name', summary: 'landing.pillar.predict.summary',
      items: [
        { title: 'landing.item.suspicious.t', detail: 'landing.item.suspicious.d', status: 'available' },
        { title: 'landing.item.drain.t', detail: 'landing.item.drain.d', status: 'partial' },
        { title: 'landing.item.anomaly.t', detail: 'landing.item.anomaly.d', status: 'available' },
        { title: 'landing.item.scoring.t', detail: 'landing.item.scoring.d', status: 'planned' },
      ],
    },
    {
      id: 'investigate', verb: 'landing.pillar.investigate.verb', name: 'landing.pillar.investigate.name', summary: 'landing.pillar.investigate.summary',
      items: [
        { title: 'landing.item.relations.t', detail: 'landing.item.relations.d', status: 'available' },
        { title: 'landing.item.graph.t', detail: 'landing.item.graph.d', status: 'available' },
        { title: 'landing.item.hops.t', detail: 'landing.item.hops.d', status: 'available' },
        { title: 'landing.item.paths.t', detail: 'landing.item.paths.d', status: 'planned' },
      ],
    },
    {
      id: 'act', verb: 'landing.pillar.act.verb', name: 'landing.pillar.act.name', summary: 'landing.pillar.act.summary',
      items: [
        { title: 'landing.item.alerts.t', detail: 'landing.item.alerts.d', status: 'available' },
        { title: 'landing.item.notify.t', detail: 'landing.item.notify.d', status: 'available' },
        { title: 'landing.item.rules.t', detail: 'landing.item.rules.d', status: 'available' },
        { title: 'landing.item.api.t', detail: 'landing.item.api.d', status: 'available' },
      ],
    },
  ],

  steps: [
    { id: 'connect', name: 'landing.step.connect.name', summary: 'landing.step.connect.sum', detail: 'landing.step.connect.det' },
    { id: 'analyze', name: 'landing.step.analyze.name', summary: 'landing.step.analyze.sum', detail: 'landing.step.analyze.det' },
    { id: 'detect', name: 'landing.step.detect.name', summary: 'landing.step.detect.sum', detail: 'landing.step.detect.det' },
    { id: 'respond', name: 'landing.step.respond.name', summary: 'landing.step.respond.sum', detail: 'landing.step.respond.det' },
  ],

  useCases: [
    { who: 'landing.use.exchange.who', value: 'landing.use.exchange.value' },
    { who: 'landing.use.fintech.who', value: 'landing.use.fintech.value' },
    { who: 'landing.use.security.who', value: 'landing.use.security.value' },
    { who: 'landing.use.aml.who', value: 'landing.use.aml.value' },
    { who: 'landing.use.web3.who', value: 'landing.use.web3.value' },
    { who: 'landing.use.research.who', value: 'landing.use.research.value' },
  ],
};
