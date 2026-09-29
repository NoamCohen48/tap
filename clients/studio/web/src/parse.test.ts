import { create, equals } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import { describeSelector } from "./describe";
import { MatchMode, NodeFlag, Relation, SelectorSchema, TextProperty, type Selector } from "./gen/selector_pb";
import { parseSelector } from "./parse";

function parsed(text: string): Selector {
  const result = parseSelector(text);
  if ("error" in result) throw new Error(`${result.error} at ${result.at}`);
  return result.selector;
}

function error(text: string): string {
  const result = parseSelector(text);
  if (!("error" in result)) throw new Error(`parsed: ${describeSelector(result.selector)}`);
  return result.error;
}

describe("parseSelector", () => {
  it.each([
    'res("search")',
    'text("Log in")',
    'textContains("Wool")',
    'textStartsWith("Wo")',
    'textMatches("Wool .*")',
    'desc("Cart")',
    'hint("Search", MatchMode.ENDS_WITH)',
    'className("android.widget.Button").enabled(false)',
    'rawRes("login_button")',
    'resId("android", "button1").inPackage("android")',
    'res("name").andText("Wool socks")',
    'res("name").andDesc("Price", MatchMode.CONTAINS)',
    'res("add").hasAncestor(res("row").hasDescendant(text("Wool")))',
    'res("row").at(2)',
    'text("OK").inAnyWindow().first()',
    'anyOf(text("Allow"), text("OK"))',
    'res("go").and(anyOf(text("Go"), desc("Go")))',
    'allOf(enabled(false), hasParent(res("row")))',
    'text("costs \\$5")',
    'text("a \\"quoted\\" word\\n")',
  ])("reads back what describeSelector prints: %s", (text) => {
    expect(describeSelector(parsed(text))).toBe(text);
  });

  it("builds the tree the SDK builds", () => {
    const expected = create(SelectorSchema, {
      node: {
        kind: {
          case: "allOf",
          value: {
            nodes: [
              { kind: { case: "resource", value: { name: "row", autPackage: true } } },
              { kind: { case: "match", value: { property: TextProperty.PROPERTY_TEXT, value: "Wool", mode: MatchMode.MATCH_EXACT } } },
              { kind: { case: "flag", value: { property: NodeFlag.FLAG_CLICKABLE, value: true } } },
            ],
          },
        },
      },
      pick: { case: "at", value: { index: 1 } },
    });
    // Infix calls, named arguments and nested conjunctions all flatten to one all_of.
    expect(equals(SelectorSchema, parsed('(res("row") and text(value = "Wool")).clickable().at(1)'), expected)).toBe(true);
    expect(equals(SelectorSchema, parsed('allOf(res("row"), text("Wool") and clickable()).at(1)'), expected)).toBe(true);
    expect(equals(SelectorSchema, parsed('res("row").andText("Wool").clickable(true).at(1)'), expected)).toBe(true);
  });

  it("reads descendant and child as the SDK: the result is the inner element", () => {
    const selector = parsed('res("list").descendant(text("Wool")).first()');
    expect(describeSelector(selector)).toBe('text("Wool").hasAncestor(res("list")).first()');
    const child = parsed('res("row").child(res("add"))');
    const related = child.node?.kind.case === "allOf" ? child.node.kind.value.nodes[1] : undefined;
    expect(related?.kind.case === "related" && related.kind.value.relation).toBe(Relation.PARENT);
  });

  it("accepts bare match modes, whitespace and single disjunctions", () => {
    expect(describeSelector(parsed(' desc( "Cart" , CONTAINS ) '))).toBe('desc("Cart", MatchMode.CONTAINS)');
    expect(describeSelector(parsed('text("A") or text("B") or desc("C")'))).toBe('anyOf(text("A"), text("B"), desc("C"))');
  });

  it("says what is wrong and where", () => {
    expect(parseSelector('res("a").frobnicate()')).toEqual({ error: "unknown call .frobnicate(…)", at: 9 });
    expect(error('res("a"')).toBe("“,” or “)” was expected, not the end");
    expect(error('text("open')).toBe("the string is not closed");
    expect(error('text("$price")')).toMatch(/template/);
    expect(error("res(3)")).toBe("res: argument 1 must be a string, not a number");
    expect(error('res("a", "b")')).toBe("res takes 1 argument");
    expect(error('text("a", MatchMode.FUZZY)')).toMatch(/no MatchMode.FUZZY/);
    expect(error('res("row").hasChild(text("a").at(1))')).toMatch(/operand picks a match/);
    expect(error('res("list").at(1).descendant(text("a"))')).toMatch(/receiver picks a match/);
    expect(error('text("a") and text("b").inPackage("android")')).toMatch(/another window scope/);
    expect(error('res("a") res("b")')).toBe("the end was expected, not “res”");
    expect(error("")).toBe("a name was expected, not the end");
  });
});
