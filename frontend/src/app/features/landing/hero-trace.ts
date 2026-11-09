import { ChangeDetectionStrategy, Component, signal } from '@angular/core';

interface LogLine { readonly at: string; readonly text: string; readonly tag?: string; readonly delay: number; readonly risk?: boolean }

/**
 * Illustrative fund-flow trace (fictional addresses and amounts, labeled as such): a wallet receives stablecoins,
 * splits them over four wallets, they are consolidated and forwarded to an exchange deposit wallet. The detectors
 * named in the evidence log (fan-out, fast-forwarding) and the rule (fund-splitting) are the real ones. Plays once on
 * load; "Replay" plays it again; static with prefers-reduced-motion.
 */
@Component({
  selector: 'oci-cs-hero-trace',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: block; }
    .panel { border: 1px solid var(--cs-line); border-radius: 14px; background: linear-gradient(180deg, var(--cs-surface), var(--cs-bg-2)); overflow: hidden; }
    .head { display: flex; align-items: center; gap: 10px; padding: 12px 16px; border-bottom: 1px solid var(--cs-line); font-size: 13px; color: var(--cs-muted); }
    .head strong { color: var(--cs-text); font-weight: 500; }
    .head .sp { flex: 1; }
    .replay { display: inline-flex; align-items: center; gap: 6px; height: 28px; padding: 0 10px; border: 1px solid var(--cs-line-2); border-radius: 6px; background: none; color: var(--cs-muted); font: 500 12px var(--cs-font); cursor: pointer; }
    .replay:hover { color: var(--cs-text); border-color: #3a4757; }
    .stage { position: relative; padding: 8px 8px 0; }
    svg { display: block; width: 100%; height: auto; }
    .edge { fill: none; stroke: var(--cs-line-2); stroke-width: 1.5; }
    .flow { fill: none; stroke: var(--cs-flow); stroke-width: 2; stroke-linecap: round; stroke-dasharray: 1; stroke-dashoffset: 0; animation: draw 0.9s cubic-bezier(.4, 0, .2, 1) both; }
    .flow.out { stroke: var(--cs-risk); }
    .node { fill: var(--cs-raised); stroke: var(--cs-line-2); stroke-width: 1.5; }
    .node.src { stroke: var(--cs-flow); }
    .node.hit { fill: #2a1416; stroke: var(--cs-risk); stroke-width: 2; }
    .node.exit { stroke: var(--cs-muted); }
    .halo { fill: none; stroke: var(--cs-risk); stroke-width: 1.5; opacity: 0; animation: halo 1.6s ease-out 3.6s 2 both; transform-origin: 400px 180px; }
    .lbl { fill: var(--cs-muted); font: 500 13px var(--cs-mono); }
    .lbl.strong { fill: var(--cs-text); }
    .lbl.risk { fill: var(--cs-risk); font-family: var(--cs-font); font-weight: 600; }
    .lbl.minor { fill: var(--cs-faint); font-family: var(--cs-font); }
    .appear { animation: appear 0.5s ease-out both; }
    .alert {
      position: absolute; right: 16px; bottom: 14px; display: grid; gap: 2px; max-width: 250px; padding: 10px 12px;
      border: 1px solid rgba(240, 100, 92, 0.45); border-radius: 8px; background: rgba(20, 12, 13, 0.92); font-size: 13px; line-height: 1.4;
      animation: appear 0.5s ease-out 5.4s both;
    }
    .alert b { color: var(--cs-risk); font-weight: 600; }
    .alert span { color: var(--cs-muted); }
    ol { list-style: none; margin: 0; padding: 12px 16px 16px; border-top: 1px solid var(--cs-line); display: grid; gap: 6px; font-size: 13px; line-height: 1.45; }
    li { display: grid; grid-template-columns: 64px 1fr auto; gap: 10px; align-items: baseline; color: var(--cs-text); animation: appear 0.45s ease-out both; }
    li time { color: var(--cs-faint); font-family: var(--cs-mono); font-size: 12px; }
    li .tag { font-family: var(--cs-mono); font-size: 12px; color: var(--cs-flow); }
    li.risk .tag { color: var(--cs-risk); }
    @keyframes draw { from { stroke-dashoffset: 1; } }
    @keyframes appear { from { opacity: 0; transform: translateY(4px); } }
    @keyframes halo { 0% { opacity: .9; transform: scale(1); } 100% { opacity: 0; transform: scale(2.1); } }
    @media (max-width: 560px) {
      .lbl { font-size: 21px; }
      .lbl.minor { display: none; }
      .alert { position: static; margin: 8px; max-width: none; }
      li { grid-template-columns: 56px 1fr; }
      li .tag { grid-column: 2; }
    }
    @media (prefers-reduced-motion: reduce) {
      .flow, .appear, .alert, li { animation: none; }
      .halo { animation: none; opacity: 0; }
    }
  `,
  template: `
    <figure class="panel" aria-labelledby="trace-caption" style="margin: 0">
      <div class="head">
        <strong id="trace-caption">Fund flow trace</strong><span>Ethereum, USDT</span><span class="sp"></span>
        <span class="cs-illus">Illustrative data</span>
        <button class="replay" type="button" (click)="run.update((r) => r + 1)" aria-label="Replay the illustration">
          <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.4" aria-hidden="true"><path d="M2 6a4 4 0 1 0 1.2-2.8M2 1.5v2h2"/></svg>Replay
        </button>
      </div>
      @for (r of [run()]; track r) {
        <div class="stage">
          <svg viewBox="0 0 560 360" role="img" aria-label="Illustration: one wallet splits 250,000 USDT over four wallets, which forward it to a consolidation wallet flagged high risk, then to an exchange deposit wallet.">
            @for (p of fanOut; track $index) { <path class="edge" [attr.d]="p" /> }
            @for (p of fanIn; track $index) { <path class="edge" [attr.d]="p" /> }
            <path class="edge" d="M400 180 L512 180" />

            @for (p of fanOut; track $index) { <path class="flow" pathLength="1" [attr.d]="p" [style.animation-delay.s]="0.5 + $index * 0.12" /> }
            @for (p of fanIn; track $index) { <path class="flow" pathLength="1" [attr.d]="p" [style.animation-delay.s]="2.0 + $index * 0.12" /> }
            <path class="flow out" pathLength="1" d="M400 180 L512 180" style="animation-delay: 4.3s" />

            <circle class="halo" cx="400" cy="180" r="22" />
            <circle class="node src" cx="64" cy="180" r="14" />
            @for (y of hops; track y) { <circle class="node appear" cx="230" [attr.cy]="y" r="9" [style.animation-delay.s]="1.0 + $index * 0.12" /> }
            <circle class="node hit appear" cx="400" cy="180" r="16" style="animation-delay: 2.9s" />
            <circle class="node exit appear" cx="512" cy="180" r="11" style="animation-delay: 4.9s" />

            <text class="lbl strong" x="64" y="148" text-anchor="middle">0x7a3f…c41e</text>
            <text class="lbl minor" x="64" y="216" text-anchor="middle">250,000 USDT in</text>
            <text class="lbl minor appear" x="230" y="30" text-anchor="middle" style="animation-delay: 1.2s">4 wallets, 47 s</text>
            <text class="lbl strong appear" x="400" y="142" text-anchor="middle" style="animation-delay: 2.9s">0x91b2…07da</text>
            <text class="lbl risk appear" x="400" y="222" text-anchor="middle" style="animation-delay: 3.6s">Risk 78, high</text>
            <text class="lbl minor appear" x="512" y="212" text-anchor="middle" style="animation-delay: 4.9s">Exchange</text>
          </svg>
          <p class="alert" role="presentation"><b>Alert raised: fund-splitting</b><span>Sent to Telegram with a link to the wallet</span></p>
        </div>
        <ol aria-label="Evidence log (illustrative)">
          @for (l of log; track l.at) {
            <li [class.risk]="l.risk" [style.animation-delay.s]="l.delay"><time>{{ l.at }}</time><span>{{ l.text }}</span>@if (l.tag) { <span class="tag">{{ l.tag }}</span> }</li>
          }
        </ol>
      }
    </figure>
  `,
})
export class HeroTrace {
  protected readonly run = signal(0);
  protected readonly hops = [64, 140, 220, 296];
  protected readonly fanOut = this.hops.map((y) => `M64 180 C 150 180, 140 ${y}, 230 ${y}`);
  protected readonly fanIn = this.hops.map((y) => `M230 ${y} C 320 ${y}, 310 180, 400 180`);
  protected readonly log: readonly LogLine[] = [
    { at: '14:02:11', text: 'Received 250,000 USDT', delay: 0.3 },
    { at: '14:02:58', text: 'Split over 4 new wallets in 47 s', tag: 'fan-out', delay: 1.3 },
    { at: '14:05:30', text: 'Consolidated into 0x91b2…07da', delay: 2.9 },
    { at: '14:05:52', text: '96% forwarded within 22 s', tag: 'fast-forwarding', delay: 3.7, risk: true },
    { at: '14:06:04', text: 'Sent to an exchange deposit wallet', delay: 4.9 },
  ];
}
