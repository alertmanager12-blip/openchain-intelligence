import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TabList } from './tab-list';

@Component({
  imports: [TabList],
  template: `<oci-tab-list [tabs]="tabs" [(selected)]="sel" prefix="t" label="Records" />`,
})
class Host {
  readonly tabs = [{ id: 'a', label: 'Alpha', count: 3 }, { id: 'b', label: 'Beta' }, { id: 'c', label: 'Gamma', count: '' }];
  readonly sel = signal('a');
}

describe('TabList', () => {
  it('follows the WAI-ARIA tabs pattern with roving tabindex and arrow keys', async () => {
    const f = TestBed.createComponent(Host);
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    const tabs = () => [...el.querySelectorAll<HTMLButtonElement>('[role="tab"]')];
    expect(el.querySelector('[role="tablist"]')?.getAttribute('aria-label')).toBe('Records');
    expect(tabs().map((t) => t.getAttribute('aria-selected'))).toEqual(['true', 'false', 'false']);
    expect(tabs().map((t) => t.tabIndex)).toEqual([0, -1, -1]);
    expect(tabs()[0].getAttribute('aria-controls')).toBe('panel-t-a');
    expect(tabs()[0].id).toBe('tab-t-a');
    expect(tabs()[0].textContent).toContain('3');
    expect(tabs()[2].querySelector('.count')).toBeNull();

    el.querySelector('[role="tablist"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    await f.whenStable();
    expect(f.componentInstance.sel()).toBe('c');
    el.querySelector('[role="tablist"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    await f.whenStable();
    expect(f.componentInstance.sel()).toBe('a');
    tabs()[1].click();
    await f.whenStable();
    expect(f.componentInstance.sel()).toBe('b');
    expect(tabs()[1].getAttribute('aria-selected')).toBe('true');
  });
});
