/**
 * Minimal YAML subset (block maps, block lists, flow lists of scalars, quoted/plain scalars, comments).
 * It covers the rule format exactly and nothing more: no anchors, tags, multi-documents or custom types,
 * so parsing can never instantiate anything but plain data.
 */
export type YamlValue = string | number | boolean | null | YamlValue[] | { [k: string]: YamlValue };

export class YamlError extends Error {
  constructor(readonly line: number, message: string) {
    super(`line ${line}: ${message}`);
    this.name = 'YamlError';
  }
}

// ---------------------------------------------------------------- emit

const RESERVED = /^(true|false|yes|no|on|off|null|~|-?\d+(\.\d+)?([eE][-+]?\d+)?|0x[0-9a-fA-F]+|\.inf|\.nan)$/i;
const PLAIN_SAFE = /^[A-Za-z0-9_./@][A-Za-z0-9_./@ -]*$/;

export function emitScalar(v: string | number | boolean | null): string {
  if (v === null) return 'null';
  if (typeof v === 'boolean') return String(v);
  if (typeof v === 'number') {
    if (!Number.isFinite(v)) throw new Error('non-finite numbers are not representable');
    return String(v);
  }
  if (v === '' || RESERVED.test(v) || !PLAIN_SAFE.test(v) || v.endsWith(' ')) return JSON.stringify(v);
  return v;
}

function isScalar(v: YamlValue): v is string | number | boolean | null {
  return v === null || typeof v !== 'object';
}

function emitMap(obj: { [k: string]: YamlValue }, indent: number): string[] {
  const pad = ' '.repeat(indent);
  const out: string[] = [];
  for (const [k, v] of Object.entries(obj)) {
    if (v === undefined) continue;
    if (isScalar(v)) out.push(`${pad}${k}: ${emitScalar(v)}`);
    else if (Array.isArray(v)) {
      if (v.length === 0) out.push(`${pad}${k}: []`);
      else if (v.every(isScalar)) out.push(`${pad}${k}: [${v.map((x) => emitScalar(x as string)).join(', ')}]`);
      else out.push(`${pad}${k}:`, ...emitList(v, indent + 2));
    } else if (Object.keys(v).length === 0) out.push(`${pad}${k}: {}`);
    else out.push(`${pad}${k}:`, ...emitMap(v, indent + 2));
  }
  return out;
}

function emitList(list: YamlValue[], indent: number): string[] {
  const pad = ' '.repeat(indent);
  const out: string[] = [];
  for (const item of list) {
    if (isScalar(item)) out.push(`${pad}- ${emitScalar(item)}`);
    else if (Array.isArray(item)) out.push(`${pad}-`, ...emitList(item, indent + 2));
    else {
      const lines = emitMap(item, indent + 2);
      if (lines.length === 0) out.push(`${pad}- {}`);
      else out.push(`${pad}- ${lines[0].slice(indent + 2)}`, ...lines.slice(1));
    }
  }
  return out;
}

export function stringify(doc: { [k: string]: YamlValue }): string {
  return emitMap(doc, 0).join('\n') + '\n';
}

// ---------------------------------------------------------------- parse

interface Line {
  no: number;
  indent: number;
  text: string;
}

function stripComment(s: string): string {
  let quote: string | null = null;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (quote) {
      if (c === '\\' && quote === '"') i++;
      else if (c === quote) quote = null;
    } else if (c === '"' || c === "'") quote = c;
    else if (c === '#' && (i === 0 || /\s/.test(s[i - 1]))) return s.slice(0, i);
  }
  return s;
}

const KEY_LINE = /^([A-Za-z_][\w.-]*)\s*:(?:\s+(.*))?$/;

export function parseScalar(raw: string, line: number): YamlValue {
  const s = raw.trim();
  if (s.startsWith('"')) {
    if (!s.endsWith('"') || s.length < 2) throw new YamlError(line, 'unterminated double-quoted string');
    try {
      return JSON.parse(s) as string;
    } catch {
      throw new YamlError(line, 'invalid escape in double-quoted string');
    }
  }
  if (s.startsWith("'")) {
    if (!s.endsWith("'") || s.length < 2) throw new YamlError(line, 'unterminated single-quoted string');
    return s.slice(1, -1).replace(/''/g, "'");
  }
  if (s.startsWith('[')) return parseFlowList(s, line);
  if (s === '{}') return {};
  if (s.startsWith('{')) throw new YamlError(line, 'flow mappings are not supported; use block style');
  if (/^[&*!|>]/.test(s)) throw new YamlError(line, `unsupported YAML feature "${s[0]}"`);
  if (s === 'true') return true;
  if (s === 'false') return false;
  if (s === 'null' || s === '~' || s === '') return null;
  if (/^-?\d+(\.\d+)?$/.test(s)) {
    const n = Number(s);
    // keep exactness: big or long decimals stay strings instead of being rounded by IEEE-754
    return String(n) === s ? n : s;
  }
  return s;
}

function parseFlowList(s: string, line: number): YamlValue[] {
  if (!s.endsWith(']')) throw new YamlError(line, 'unterminated flow list');
  const body = s.slice(1, -1).trim();
  if (!body) return [];
  const parts: string[] = [];
  let cur = '';
  let quote: string | null = null;
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (quote) {
      cur += c;
      if (c === '\\' && quote === '"') cur += body[++i] ?? '';
      else if (c === quote) quote = null;
    } else if (c === '"' || c === "'") {
      quote = c;
      cur += c;
    } else if (c === '[' || c === '{') throw new YamlError(line, 'nested flow collections are not supported');
    else if (c === ',') {
      parts.push(cur);
      cur = '';
    } else cur += c;
  }
  if (quote) throw new YamlError(line, 'unterminated string in flow list');
  parts.push(cur);
  return parts.map((p) => {
    if (!p.trim()) throw new YamlError(line, 'empty item in flow list');
    return parseScalar(p, line);
  });
}

class Parser {
  private i = 0;
  constructor(private readonly lines: Line[]) {}

  parseDocument(): YamlValue {
    if (this.lines.length === 0) return null;
    const first = this.lines[0];
    if (first.indent !== 0) throw new YamlError(first.no, 'document must start at column 0');
    const v = this.block(0);
    if (this.i < this.lines.length) throw new YamlError(this.lines[this.i].no, 'unexpected indentation');
    return v;
  }

  private peek(): Line | undefined {
    return this.lines[this.i];
  }

  private isItem(l: Line): boolean {
    return l.text === '-' || l.text.startsWith('- ');
  }

  private block(indent: number): YamlValue {
    const l = this.peek()!;
    if (this.isItem(l)) return this.list(indent);
    if (KEY_LINE.test(l.text)) return this.map(indent);
    if (this.lines.length === 1 || this.i === this.lines.length - 1) {
      this.i++;
      return parseScalar(l.text, l.no);
    }
    throw new YamlError(l.no, `expected "key: value" or "- item", got "${l.text}"`);
  }

  private nested(parentIndent: number, allowSameIndentList: boolean): YamlValue {
    const next = this.peek();
    if (!next) return null;
    if (next.indent > parentIndent) return this.block(next.indent);
    if (allowSameIndentList && next.indent === parentIndent && this.isItem(next)) return this.list(parentIndent);
    return null;
  }

  private map(indent: number): { [k: string]: YamlValue } {
    const out: { [k: string]: YamlValue } = {};
    for (let l = this.peek(); l && l.indent === indent && !this.isItem(l); l = this.peek()) {
      const m = KEY_LINE.exec(l.text);
      if (!m) throw new YamlError(l.no, `expected "key: value", got "${l.text}"`);
      const [, key, rest] = m;
      if (Object.prototype.hasOwnProperty.call(out, key)) throw new YamlError(l.no, `duplicate key "${key}"`);
      if (key === '__proto__' || key === 'constructor' || key === 'prototype') throw new YamlError(l.no, `forbidden key "${key}"`);
      this.i++;
      out[key] = rest === undefined || rest.trim() === '' ? this.nested(indent, true) : parseScalar(rest, l.no);
    }
    const l = this.peek();
    if (l && l.indent > indent) throw new YamlError(l.no, 'unexpected indentation');
    return out;
  }

  private list(indent: number): YamlValue[] {
    const out: YamlValue[] = [];
    for (let l = this.peek(); l && l.indent === indent && this.isItem(l); l = this.peek()) {
      const content = l.text === '-' ? '' : l.text.slice(1).trimStart();
      if (!content) {
        this.i++;
        out.push(this.nested(indent, false));
      } else if (KEY_LINE.test(content)) {
        // "- key: v" starts a map whose keys sit at the column of "key"
        const col = indent + (l.text.length - content.length);
        this.lines[this.i] = { no: l.no, indent: col, text: content };
        out.push(this.map(col));
      } else {
        this.i++;
        out.push(parseScalar(content, l.no));
      }
    }
    const l = this.peek();
    if (l && l.indent > indent) throw new YamlError(l.no, 'unexpected indentation');
    return out;
  }
}

export function parse(text: string): YamlValue {
  const lines: Line[] = [];
  text.replace(/\r\n?/g, '\n').split('\n').forEach((raw, idx) => {
    const no = idx + 1;
    const lead = /^[ \t]*/.exec(raw)![0];
    if (lead.includes('\t')) throw new YamlError(no, 'tabs are not allowed for indentation');
    const body = stripComment(raw).trimEnd();
    if (!body.trim()) return;
    if (no === 1 && body.trim() === '---') return;
    lines.push({ no, indent: lead.length, text: body.slice(lead.length) });
  });
  return new Parser(lines).parseDocument();
}
