// A typed selector, read back into the message: the Kotlin SDK's selector DSL
// (`res("row").andText("Wool socks").hasDescendant(desc("Add"))`, `text("Allow") or text("OK")`,
// `.inPackage("android").at(2)`), which is also what `describeSelector` prints. It builds the tree
// the SDK builds: conjunctions and disjunctions flattened, the same operand rules. Beyond the DSL it
// accepts every flag and `has*` relation as a factory, which `describeSelector` needs for a selector
// that has only those (`allOf(enabled(false), hasParent(res("row")))`).

import { create, equals } from "@bufbuild/protobuf";
import { FACTORY, FLAGS, MODE_NAMES, RELATIONS } from "./describe";
import {
  AnyWindowScopeSchema,
  AtSchema,
  FirstSchema,
  MatchMode,
  NodeFlag,
  NodeSchema,
  Relation,
  SelectorSchema,
  SystemScopeSchema,
  TextProperty,
  type Node,
  type Selector,
} from "./gen/selector_pb";

export type Parsed = { selector: Selector } | { error: string; at: number };

type Scope = Selector["scope"];
type Pick = Selector["pick"];
type Sel = { node: Node; scope: Scope; pick: Pick };
type Value =
  | { kind: "selector"; value: Sel }
  | { kind: "string"; value: string }
  | { kind: "number"; value: number }
  | { kind: "boolean"; value: boolean }
  | { kind: "mode"; value: MatchMode };
type Token = { kind: "name" | "string" | "number" | "punct" | "end"; text: string; at: number; value?: string };

class ParseError extends Error {
  constructor(
    message: string,
    readonly at: number,
  ) {
    super(message);
  }
}

export function parseSelector(text: string): Parsed {
  try {
    const parser = new Parser(tokenize(text));
    const sel = parser.expression();
    parser.expect("end");
    return { selector: create(SelectorSchema, { node: sel.node, scope: sel.scope, pick: sel.pick }) };
  } catch (error) {
    if (error instanceof ParseError) return { error: error.message, at: error.at };
    throw error;
  }
}

// ---- tokens -------------------------------------------------------------------------------------

const ESCAPES: Record<string, string> = { n: "\n", t: "\t", r: "\r", b: "\b", '"': '"', "'": "'", "\\": "\\", $: "$" };

function tokenize(text: string): Token[] {
  const tokens: Token[] = [];
  let i = 0;
  while (i < text.length) {
    const c = text[i]!;
    if (/\s/.test(c)) {
      i++;
    } else if (/[A-Za-z_]/.test(c)) {
      const start = i;
      while (i < text.length && /[A-Za-z0-9_]/.test(text[i]!)) i++;
      tokens.push({ kind: "name", text: text.slice(start, i), at: start });
    } else if (/[0-9-]/.test(c)) {
      const start = i;
      i++;
      while (i < text.length && /[0-9]/.test(text[i]!)) i++;
      if (text.slice(start, i) === "-") throw new ParseError("a number was expected after -", start);
      tokens.push({ kind: "number", text: text.slice(start, i), at: start });
    } else if (c === '"') {
      const start = i;
      let value = "";
      i++;
      for (;;) {
        if (i >= text.length) throw new ParseError("the string is not closed", start);
        const d = text[i]!;
        if (d === '"') break;
        if (d === "$" && /[A-Za-z_{]/.test(text[i + 1] ?? "")) {
          throw new ParseError("a Kotlin template: write \\$ for a dollar sign", i);
        }
        if (d === "\\") {
          const e = text[i + 1] ?? "";
          if (e === "u" && /^[0-9a-fA-F]{4}$/.test(text.slice(i + 2, i + 6))) {
            value += String.fromCharCode(parseInt(text.slice(i + 2, i + 6), 16));
            i += 6;
            continue;
          }
          if (!(e in ESCAPES)) throw new ParseError(`unknown escape \\${e}`, i);
          value += ESCAPES[e];
          i += 2;
          continue;
        }
        value += d;
        i++;
      }
      i++;
      tokens.push({ kind: "string", text: text.slice(start, i), at: start, value });
    } else if ("().,=".includes(c)) {
      tokens.push({ kind: "punct", text: c, at: i });
      i++;
    } else {
      throw new ParseError(`unexpected ${JSON.stringify(c)}`, i);
    }
  }
  tokens.push({ kind: "end", text: "", at: text.length });
  return tokens;
}

// ---- nodes (as the SDK builds them) -------------------------------------------------------------

function conjunction(...nodes: Node[]): Node {
  const flat = nodes.flatMap((n) => (n.kind.case === "allOf" ? n.kind.value.nodes : [n]));
  return flat.length === 1 ? flat[0]! : create(NodeSchema, { kind: { case: "allOf", value: { nodes: flat } } });
}

function disjunction(...nodes: Node[]): Node {
  const flat = nodes.flatMap((n) => (n.kind.case === "anyOf" ? n.kind.value.nodes : [n]));
  return flat.length === 1 ? flat[0]! : create(NodeSchema, { kind: { case: "anyOf", value: { nodes: flat } } });
}

const bare = (n: Node): Sel => ({ node: n, scope: { case: undefined }, pick: { case: undefined } });

function match(property: TextProperty, value: string, mode = MatchMode.MATCH_EXACT): Node {
  return create(NodeSchema, { kind: { case: "match", value: { property, value, mode } } });
}

function flag(property: NodeFlag, value: boolean): Node {
  return create(NodeSchema, { kind: { case: "flag", value: { property, value } } });
}

function related(relation: Relation, target: Node): Node {
  return create(NodeSchema, { kind: { case: "related", value: { relation, node: target } } });
}

const reverse = <K extends number>(table: Record<K, string>) =>
  new Map(Object.entries(table).map(([k, v]) => [v as string, Number(k) as K]));

const MATCH_FACTORIES = new Map<string, TextProperty>(
  [TextProperty.PROPERTY_TEXT, TextProperty.PROPERTY_CONTENT_DESCRIPTION, TextProperty.PROPERTY_HINT, TextProperty.PROPERTY_CLASS_NAME].map(
    (p) => [FACTORY[p], p],
  ),
);
const TEXT_SHORTHANDS = new Map<string, MatchMode>([
  ["textContains", MatchMode.MATCH_CONTAINS],
  ["textStartsWith", MatchMode.MATCH_STARTS_WITH],
  ["textMatches", MatchMode.MATCH_REGEX],
]);
const MODES = new Map([...reverse(MODE_NAMES)].filter(([, mode]) => mode !== MatchMode.MATCH_UNSPECIFIED));
const FLAG_NAMES = new Map([...reverse(FLAGS)].filter(([, f]) => f !== NodeFlag.FLAG_UNSPECIFIED));
const RELATION_NAMES = new Map([...reverse(RELATIONS)].filter(([, r]) => r !== Relation.UNSPECIFIED));

// ---- the parser ---------------------------------------------------------------------------------

class Parser {
  private i = 0;

  constructor(private readonly tokens: Token[]) {}

  private get token(): Token {
    return this.tokens[this.i]!;
  }

  private peek(offset = 1): Token {
    return this.tokens[Math.min(this.i + offset, this.tokens.length - 1)]!;
  }

  private is(kind: Token["kind"], text?: string): boolean {
    return this.token.kind === kind && (text === undefined || this.token.text === text);
  }

  expect(kind: Token["kind"], text?: string): Token {
    if (!this.is(kind, text)) {
      const wanted = text ? `“${text}”` : kind === "end" ? "the end" : `a ${kind}`;
      throw new ParseError(`${wanted} was expected, not ${this.describe(this.token)}`, this.token.at);
    }
    return this.tokens[this.i++]!;
  }

  private describe(token: Token): string {
    return token.kind === "end" ? "the end" : `“${token.text}”`;
  }

  /** Infix `and` / `or`, left to right, as Kotlin's infix calls. */
  expression(): Sel {
    let left = this.chain();
    while (this.is("name", "and") || this.is("name", "or")) {
      const at = this.token.at;
      const operator = this.expect("name").text;
      const right = this.chain();
      left = operator === "and" ? this.and(left, right, at) : this.or(left, right, at);
    }
    return left;
  }

  private chain(): Sel {
    let sel: Sel;
    if (this.is("punct", "(")) {
      this.i++;
      sel = this.expression();
      this.expect("punct", ")");
    } else {
      const name = this.expect("name");
      sel = this.factory(name.text, this.arguments(), name.at);
    }
    while (this.is("punct", ".")) {
      this.i++;
      const name = this.expect("name");
      sel = this.method(sel, name.text, this.arguments(), name.at);
    }
    return sel;
  }

  private arguments(): Value[] {
    this.expect("punct", "(");
    const values: Value[] = [];
    while (!this.is("punct", ")")) {
      if (values.length) {
        if (!this.is("punct", ",")) throw new ParseError(`“,” or “)” was expected, not ${this.describe(this.token)}`, this.token.at);
        this.i++;
      }
      if (this.is("name") && this.peek().kind === "punct" && this.peek().text === "=") this.i += 2; // a named argument
      values.push(this.value());
    }
    this.expect("punct", ")");
    return values;
  }

  private value(): Value {
    const token = this.token;
    if (token.kind === "string") {
      this.i++;
      return { kind: "string", value: token.value! };
    }
    if (token.kind === "number") {
      this.i++;
      return { kind: "number", value: Number(token.text) };
    }
    if (token.kind === "name" && (token.text === "true" || token.text === "false")) {
      this.i++;
      return { kind: "boolean", value: token.text === "true" };
    }
    if (token.kind === "name" && token.text === "MatchMode" && this.peek().text === ".") {
      this.i += 2;
      return this.mode();
    }
    if (token.kind === "name" && MODES.has(token.text) && this.peek().text !== "(") return this.mode();
    return { kind: "selector", value: this.expression() };
  }

  private mode(): Value {
    const name = this.expect("name");
    const mode = MODES.get(name.text);
    if (mode === undefined) throw new ParseError(`no MatchMode.${name.text}: use ${[...MODES.keys()].join(", ")}`, name.at);
    return { kind: "mode", value: mode };
  }

  private factory(name: string, args: Value[], at: number): Sel {
    const property = MATCH_FACTORIES.get(name);
    if (property !== undefined) {
      const [value, mode] = this.take(name, args, at, ["string"], ["mode"]);
      return bare(match(property, value as string, (mode as MatchMode | undefined) ?? MatchMode.MATCH_EXACT));
    }
    const shorthand = TEXT_SHORTHANDS.get(name);
    if (shorthand !== undefined) {
      const [value] = this.take(name, args, at, ["string"]);
      return bare(match(TextProperty.PROPERTY_TEXT, value as string, shorthand));
    }
    switch (name) {
      case "res": {
        const [resource] = this.take(name, args, at, ["string"]);
        return bare(create(NodeSchema, { kind: { case: "resource", value: { name: resource as string, autPackage: true } } }));
      }
      case "rawRes": {
        const [resource] = this.take(name, args, at, ["string"]);
        return bare(create(NodeSchema, { kind: { case: "resource", value: { name: resource as string } } }));
      }
      case "resId": {
        const [packageName, resource] = this.take(name, args, at, ["string", "string"]);
        return bare(
          create(NodeSchema, { kind: { case: "resource", value: { name: resource as string, packageName: packageName as string } } }),
        );
      }
      case "anyOf":
      case "allOf": {
        const operands = args.map((a) => this.selector(name, a, at));
        if (!operands.length) throw new ParseError(`${name} needs at least one selector`, at);
        return operands.slice(1).reduce((acc, next) => (name === "anyOf" ? this.or(acc, next, at) : this.and(acc, next, at)), operands[0]!);
      }
    }
    const f = FLAG_NAMES.get(name);
    if (f !== undefined) {
      const [value] = this.take(name, args, at, [], ["boolean"]);
      return bare(flag(f, (value as boolean | undefined) ?? true));
    }
    const relation = RELATION_NAMES.get(name);
    if (relation !== undefined) {
      const [other] = this.take(name, args, at, ["selector"]);
      return bare(related(relation, this.operand(name, bare(create(NodeSchema)), other as Sel, at).node));
    }
    throw new ParseError(`unknown selector ${name}(…)`, at);
  }

  private method(sel: Sel, name: string, args: Value[], at: number): Sel {
    const also = (n: Node): Sel => ({ ...sel, node: conjunction(sel.node, n) });
    const refinement = /^and([A-Z]\w*)$/.exec(name);
    if (refinement && name !== "andRes") {
      const factory = refinement[1]![0]!.toLowerCase() + refinement[1]!.slice(1);
      const property = MATCH_FACTORIES.get(factory);
      if (property === undefined) throw new ParseError(`unknown refinement .${name}(…)`, at);
      const [value, mode] = this.take(name, args, at, ["string"], ["mode"]);
      return also(match(property, value as string, (mode as MatchMode | undefined) ?? MatchMode.MATCH_EXACT));
    }
    switch (name) {
      case "andRes": {
        const [first, second] = this.take(name, args, at, ["string"], ["string"]);
        const value =
          second === undefined ? { name: first as string, autPackage: true } : { name: second as string, packageName: first as string };
        return also(create(NodeSchema, { kind: { case: "resource", value } }));
      }
      case "and":
        return this.and(sel, this.take(name, args, at, ["selector"])[0] as Sel, at);
      case "or":
        return this.or(sel, this.take(name, args, at, ["selector"])[0] as Sel, at);
      case "descendant":
      case "child": {
        const [other] = this.take(name, args, at, ["selector"]);
        if (sel.pick.case) throw new ParseError(`${name}: the receiver picks a match (first()/at()); pick on the result instead`, at);
        const target = other as Sel;
        this.sameScope(name, sel, target, at);
        const relation = name === "descendant" ? Relation.ANCESTOR : Relation.PARENT;
        return { node: conjunction(target.node, related(relation, sel.node)), scope: sel.scope, pick: target.pick };
      }
      case "inPackage": {
        const [packageName] = this.take(name, args, at, ["string"]);
        return { ...sel, scope: { case: "system", value: create(SystemScopeSchema, { packageName: packageName as string }) } };
      }
      case "inAnyWindow":
        this.take(name, args, at, []);
        return { ...sel, scope: { case: "anyWindow", value: create(AnyWindowScopeSchema) } };
      case "first":
        this.take(name, args, at, []);
        return { ...sel, pick: { case: "first", value: create(FirstSchema) } };
      case "at": {
        const [index] = this.take(name, args, at, ["number"]);
        if ((index as number) < 0) throw new ParseError("at(…) takes an index ≥ 0", at);
        return { ...sel, pick: { case: "at", value: create(AtSchema, { index: index as number }) } };
      }
    }
    const f = FLAG_NAMES.get(name);
    if (f !== undefined) {
      const [value] = this.take(name, args, at, [], ["boolean"]);
      return also(flag(f, (value as boolean | undefined) ?? true));
    }
    const relation = RELATION_NAMES.get(name);
    if (relation !== undefined) {
      const [other] = this.take(name, args, at, ["selector"]);
      return also(related(relation, this.operand(name, sel, other as Sel, at).node));
    }
    throw new ParseError(`unknown call .${name}(…)`, at);
  }

  private and(left: Sel, right: Sel, at: number): Sel {
    return { ...left, node: conjunction(left.node, this.operand("and", left, right, at).node) };
  }

  private or(left: Sel, right: Sel, at: number): Sel {
    return { ...left, node: disjunction(left.node, this.operand("or", left, right, at).node) };
  }

  /** An operand of and / or / has*: a bare predicate, so it can carry no pick or other scope. */
  private operand(name: string, receiver: Sel, other: Sel, at: number): Sel {
    if (other.pick.case) throw new ParseError(`${name}: the operand picks a match (first()/at()), which a predicate cannot carry`, at);
    this.sameScope(name, receiver, other, at);
    return other;
  }

  private sameScope(name: string, receiver: Sel, other: Sel, at: number): void {
    if (
      other.scope.case &&
      !equals(SelectorSchema, create(SelectorSchema, { scope: other.scope }), create(SelectorSchema, { scope: receiver.scope }))
    ) {
      throw new ParseError(`${name}: the operand has another window scope`, at);
    }
  }

  private selector(name: string, value: Value, at: number): Sel {
    if (value.kind === "selector") return value.value;
    throw new ParseError(`${name} takes selectors`, at);
  }

  /** The arguments by kind: `required` then `optional`; the values unwrapped. */
  private take(name: string, args: Value[], at: number, required: Value["kind"][], optional: Value["kind"][] = []): unknown[] {
    const kinds = [...required, ...optional];
    if (args.length < required.length || args.length > kinds.length) {
      const count = optional.length ? `${required.length} to ${kinds.length}` : `${required.length}`;
      throw new ParseError(`${name} takes ${count} argument${kinds.length === 1 ? "" : "s"}`, at);
    }
    return args.map((arg, index) => {
      if (arg.kind !== kinds[index])
        throw new ParseError(`${name}: argument ${index + 1} must be a ${kinds[index]}, not a ${arg.kind}`, at);
      return arg.value;
    });
  }
}
