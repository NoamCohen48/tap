import { create } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import { describeSelector, describeStep, describeWait, stepKind } from "./describe";
import { Direction, SystemPanel } from "./gen/command_pb";
import { MatchMode, NodeFlag, Relation, SelectorSchema, TextProperty, type Node } from "./gen/selector_pb";
import { Condition, PerformRequestSchema, StepSchema, type PerformRequest } from "./gen/studio_pb";
import * as steps from "./steps";

const res = (name: string): Node => ({ kind: { case: "resource", value: { name } } }) as Node;
const match = (property: TextProperty, value: string, mode = MatchMode.MATCH_EXACT): Node =>
  ({ kind: { case: "match", value: { property, value, mode } } }) as Node;
const all = (...nodes: Node[]): Node => ({ kind: { case: "allOf", value: { nodes } } }) as Node;
const selector = (node: Node, extra: object = {}) => create(SelectorSchema, { node, ...extra });

describe("describeSelector", () => {
  it.each([
    [selector(res("search")), 'res("search")'],
    [selector(match(TextProperty.PROPERTY_TEXT, "Log in")), 'text("Log in")'],
    [selector(match(TextProperty.PROPERTY_TEXT, "Wool", MatchMode.MATCH_CONTAINS)), 'textContains("Wool")'],
    [selector(match(TextProperty.PROPERTY_CONTENT_DESCRIPTION, "Cart")), 'desc("Cart")'],
    [selector(match(TextProperty.PROPERTY_HINT, "Search", MatchMode.MATCH_ENDS_WITH)), 'hint("Search", MatchMode.ENDS_WITH)'],
    [selector(all(res("name"), match(TextProperty.PROPERTY_TEXT, "Wool socks"))), 'res("name").andText("Wool socks")'],
    [
      selector(
        all(res("add"), {
          kind: {
            case: "related",
            value: {
              relation: Relation.ANCESTOR,
              node: all(res("row"), {
                kind: { case: "related", value: { relation: Relation.DESCENDANT, node: match(TextProperty.PROPERTY_TEXT, "Wool") } },
              } as Node),
            },
          },
        } as Node),
      ),
      'res("add").hasAncestor(res("row").hasDescendant(text("Wool")))',
    ],
    [selector(res("row"), { pick: { case: "at", value: { index: 2 } } }), 'res("row").at(2)'],
    [
      selector(
        all(
          { kind: { case: "resource", value: { name: "button1", packageName: "android" } } } as Node,
          match(TextProperty.PROPERTY_PACKAGE_NAME, "android"),
        ),
      ),
      'resId("android", "button1").andPackageName("android")',
    ],
    [
      selector(
        all(
          { kind: { case: "flag", value: { property: NodeFlag.FLAG_ENABLED, value: false } } } as Node,
          match(TextProperty.PROPERTY_CLASS_NAME, "android.widget.Button"),
        ),
      ),
      'className("android.widget.Button").enabled(false)',
    ],
  ])("%#: %s", (given, expected) => {
    expect(describeSelector(given)).toBe(expected);
  });
});

const recorded = (request: PerformRequest) => request.step!;
const target = (node: Node) => steps.synthesized(selector(node));

describe("describeStep", () => {
  const search = steps.synthesized(selector(res("search")));

  it("shows each kind as the SDK call it replays as", () => {
    expect(describeStep(recorded(steps.gesture(search, "tap")))).toBe('screen.element(res("search")).tap()');
    expect(describeStep(recorded(steps.gesture(search, "longTap")))).toBe('screen.element(res("search")).longTap()');
    expect(describeStep(recorded(steps.scroll(search, Direction.DIR_DOWN)))).toBe('screen.element(res("search")).scroll(DOWN)');
    expect(describeStep(recorded(steps.swipe(search, Direction.DIR_LEFT)))).toBe('screen.element(res("search")).swipe(LEFT)');
    expect(describeStep(recorded(steps.setText(search, { text: "wool" })))).toBe('screen.element(res("search")).setText("wool")');
    expect(describeStep(recorded(steps.setText(search, { secret: "password", value: "hunter2" })))).toBe(
      'screen.element(res("search")).setText(${password})',
    );
    expect(describeStep(recorded(steps.typeText(search, { text: "jo" })))).toBe('screen.element(res("search")).typeText("jo")');
    expect(describeStep(recorded(steps.pressKey(4)))).toBe("pressBack()");
    expect(describeStep(recorded(steps.pressKey(66)))).toBe("pressKey(66)");
    expect(describeStep(recorded(steps.openSystemPanel(SystemPanel.NOTIFICATIONS)))).toBe("openNotifications()");
    expect(describeStep(recorded(steps.openSystemPanel(SystemPanel.QUICK_SETTINGS)))).toBe("openQuickSettings()");
    expect(describeStep(recorded(steps.app("cold_launch", "com.example")))).toBe('app("com.example").coldLaunch()');
    expect(describeStep(recorded(steps.app("grant_permission", "com.example", "android.permission.CAMERA")))).toBe(
      'app("com.example").grantPermission("android.permission.CAMERA")',
    );
    expect(describeStep(recorded(steps.assertion(search, { condition: Condition.TEXT_EQUALS, text: "Wool" })))).toBe(
      'screen.await(res("search")).textEquals("Wool")',
    );
    expect(describeStep(recorded(steps.assertion(search, { condition: Condition.ONE })))).toBe('screen.await(res("search")).one()');
  });

  it("runs a selector with a package predicate on that app, and one without on the screen", () => {
    const owned = steps.synthesized(selector(all(res("search"), match(TextProperty.PROPERTY_PACKAGE_NAME, "com.example"))));
    expect(describeStep(recorded(steps.gesture(owned, "tap")))).toBe('app("com.example").element(res("search")).tap()');
    expect(describeStep(recorded(steps.assertion(owned, { condition: Condition.GONE })))).toBe('app("com.example").await(res("search")).gone()');
    // Only an exact predicate in the top conjunction is the app: a nested one stays in the selector.
    const prefix = steps.synthesized(selector(all(res("a"), match(TextProperty.PROPERTY_PACKAGE_NAME, "com.", MatchMode.MATCH_STARTS_WITH))));
    expect(describeStep(recorded(steps.gesture(prefix, "tap")))).toBe(
      'screen.element(res("a").andPackageName("com.", MatchMode.STARTS_WITH)).tap()',
    );
  });

  it("names the step kinds", () => {
    expect(stepKind(recorded(steps.pressKey(4)))).toBe("key");
    expect(stepKind(recorded(steps.openSystemPanel(SystemPanel.QUICK_SETTINGS)))).toBe("system");
    expect(stepKind(recorded(steps.gesture(search, "tap")))).toBe("action");
    expect(stepKind(recorded(steps.assertion(search, { condition: Condition.VISIBLE })))).toBe("assertion");
  });

  it("shows the wait the studio inferred for an action", () => {
    const step = create(StepSchema, {
      kind: {
        case: "action",
        value: {
          command: { op: { case: "tap", value: { selector: search.selector } } },
          wait: { op: { case: "waitVisible", value: { selector: search.selector, exactlyOne: true } } },
        },
      },
    });
    expect(describeWait(step)).toBe('screen.await(res("search")).one()');
    expect(describeWait(recorded(steps.pressKey(4)))).toBeNull();
    expect(describeWait(recorded(steps.openSystemPanel(SystemPanel.NOTIFICATIONS)))).toBeNull();
  });
});

describe("steps", () => {
  it("sends a secret's value beside the step and keeps it out of the step", () => {
    const request = steps.setText(target(res("pin")), { secret: "pin", value: "1234" });
    expect(request.secretValue).toBe("1234");
    const action = request.step!.kind.case === "action" ? request.step!.kind.value : null;
    expect(action?.secret).toBe("pin");
    expect(action?.command?.op.case === "setText" && action.command.op.value.text).toBe("");
    expect(JSON.stringify(create(PerformRequestSchema, request).step)).not.toContain("1234");
  });

  it("leaves the wait and the gesture distance to the studio", () => {
    const action = steps.swipe(target(res("pager")), Direction.DIR_LEFT).step!.kind;
    expect(action.case === "action" && action.value.wait).toBeUndefined();
    expect(
      action.case === "action" && action.value.command?.op.case === "swipe" && action.value.command.op.value.distancePercent,
    ).toBeUndefined();
  });
});
