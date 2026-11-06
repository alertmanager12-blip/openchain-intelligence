import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Dashboard } from '../../core/models';

/** Events indexed per hour over 24 hours, with a mark on each hour in which an alert fired. Deferred until visible. */
@Component({
  selector: 'oci-activity-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (activity().length) {
      <div class="well chart">
        <svg viewBox="0 0 240 72" preserveAspectRatio="none" role="img" [attr.aria-label]="aria()">
          @for (t of [0.25, 0.5, 0.75]; track t) { <line x1="0" x2="240" [attr.y1]="72 - t * 64" [attr.y2]="72 - t * 64" class="grid" /> }
          @for (b of activity(); track b.start; let i = $index) {
            <rect [attr.x]="i * 10 + 1.5" width="7" [attr.y]="72 - (b.events / max()) * 64" [attr.height]="(b.events / max()) * 64" rx="1" class="ev"><title>{{ b.start.slice(11, 16) }} UTC: {{ b.events }} events, {{ b.alerts ?? 0 }} alerts</title></rect>
            @if (b.alerts) { <rect [attr.x]="i * 10 + 1.5" width="7" y="0" height="3" rx="1" class="al" /> }
          }
        </svg>
        <div class="axis"><span>24 h ago</span><span>Peak {{ max() }} per hour</span><span>Now</span></div>
      </div>
      <p class="small muted">Bars: events indexed per hour. Marks on top: hours in which at least one alert fired.</p>
    } @else { <p class="muted">No activity recorded in the last 24 hours.</p> }
  `,
  styles: `
    .chart { padding: 14px 14px 8px; }
    svg { width: 100%; height: 120px; display: block; }
    .grid { stroke: var(--line); stroke-dasharray: 2 3; vector-effect: non-scaling-stroke; }
    .ev { fill: var(--accent); opacity: 0.75; }
    .al { fill: var(--risk-high); }
    .axis { display: flex; justify-content: space-between; margin-top: 6px; font-size: 12px; color: var(--muted); }
    p { margin: 8px 0 0; }
  `,
})
export class ActivityChart {
  readonly activity = input.required<Dashboard['activity']>();
  readonly max = computed(() => Math.max(1, ...this.activity().map((a) => a.events)));
  readonly aria = computed(() => `Events per hour over the last 24 hours, peak ${this.max()}, ${this.activity().reduce((s, x) => s + (x.alerts ?? 0), 0)} alerts in total`);
}
