import { graphDiff } from './graph-model';

const el = (id: string) => ({ data: { id } });

describe('graphDiff', () => {
  it('removes what no longer matches and adds what is new', () => {
    const d = graphDiff(['root', 'a', 'b', 'e:root-a', 'e:root-b'], [el('root'), el('a'), el('c'), el('e:root-a'), el('e:root-c')]);
    expect(d.add.map((e) => e.data.id)).toEqual(['c', 'e:root-c']);
    expect(d.removeIds).toEqual(['b', 'e:root-b']);
  });
  it('a narrower filter on the same root only removes', () => {
    const d = graphDiff(['root', 'a', 'b', 'e1', 'e2'], [el('root'), el('a'), el('e1')]);
    expect(d.add).toEqual([]);
    expect(d.removeIds).toEqual(['b', 'e2']);
  });
  it('an unchanged graph needs nothing', () => {
    const d = graphDiff(['root', 'a'], [el('root'), el('a')]);
    expect(d.add).toEqual([]);
    expect(d.removeIds).toEqual([]);
  });
});
