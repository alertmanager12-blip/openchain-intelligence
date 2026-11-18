import { TestBed } from '@angular/core/testing';
import { EvidenceLine } from './evidence-line';

describe('EvidenceLine', () => {
  function render(inputs: Record<string, unknown>) {
    const f = TestBed.createComponent(EvidenceLine);
    for (const [k, v] of Object.entries(inputs)) f.componentRef.setInput(k, v);
    f.detectChanges();
    return f;
  }

  it('shows the level word, the text and the detail', () => {
    const el: HTMLElement = render({ level: 'INFERRED', text: '1,000 USDT received from TSanct', detail: 'risk signal' }).nativeElement;
    expect(el.textContent).toContain('Inferred');
    expect(el.textContent).toContain('1,000 USDT received from TSanct');
    expect(el.textContent).toContain('risk signal');
    expect(el.querySelector('.line')?.classList).toContain('lvl-inferred');
  });

  it('emits the cited id', () => {
    const f = render({ level: 'CONFIRMED', text: 'Listed as sanctioned by OFAC SDN', cite: 'as_42' });
    let got: string | undefined;
    f.componentInstance.citeClick.subscribe((v: string) => (got = v));
    (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button.cite')!.click();
    expect(got).toBe('as_42');
  });

  it('shows no cite button without a citation', () => {
    expect((render({ level: 'OBSERVED', text: 'x' }).nativeElement as HTMLElement).querySelector('button.cite')).toBeNull();
  });
});
