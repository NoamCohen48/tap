import { create } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import { describeSelector, describeStep, describeWait, stepKind } from "./describe";
import { IntentExtraSchema } from "./gen/app_pb";
import { Direction, DisplayRotation, LocationAccuracy, Orientation, PermissionChoice, StandardAction, SystemPanel } from "./gen/command_pb";
import { MatchMode, NodeFlag, Relation, SelectorSchema, TextProperty, type Node } from "./gen/selector_pb";
import { Check, Condition, PerformRequestSchema, StepSchema, type PerformRequest } from "./gen/studio_pb";
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
    expect(describeStep(recorded(steps.swipe(search, Direction.DIR_UP, 40)))).toBe('screen.element(res("search")).swipe(UP, distancePercent = 40)');
    expect(describeStep(recorded(steps.scroll(search, Direction.DIR_DOWN, 80)))).toBe('screen.element(res("search")).scroll(DOWN)');
    expect(describeStep(recorded(steps.setText(search, { text: "wool" })))).toBe('screen.element(res("search")).setText("wool")');
    expect(describeStep(recorded(steps.setText(search, { secret: "password", value: "hunter2" })))).toBe(
      'screen.element(res("search")).setText(${password})',
    );
    expect(describeStep(recorded(steps.typeText(search, { text: "jo" })))).toBe('screen.element(res("search")).typeText("jo")');
    expect(describeStep(recorded(steps.typeText(search, { text: "jo" }, { awaitFocus: false })))).toBe(
      'screen.element(res("search")).typeText("jo", awaitFocus = false)',
    );
    expect(describeStep(recorded(steps.app("launch", "com.example", { activity: ".ui.Settings" })))).toBe('app("com.example").launch(".ui.Settings")');
    expect(describeStep(recorded(steps.pressKey(4)))).toBe("pressBack()");
    expect(describeStep(recorded(steps.pressKey(66)))).toBe("pressKey(66 /* Enter */)");
    expect(describeStep(recorded(steps.pressKey(300)))).toBe("pressKey(300)");
    expect(describeStep(recorded(steps.openSystemPanel(SystemPanel.NOTIFICATIONS)))).toBe("openNotifications()");
    expect(describeStep(recorded(steps.openSystemPanel(SystemPanel.QUICK_SETTINGS)))).toBe("openQuickSettings()");
    expect(describeStep(recorded(steps.app("cold_launch", "com.example")))).toBe('app("com.example").coldLaunch()');
    expect(describeStep(recorded(steps.app("grant_permission", "com.example", { permission: "android.permission.CAMERA" })))).toBe(
      'app("com.example").grantPermission("android.permission.CAMERA")',
    );
    expect(describeStep(recorded(steps.wait(search, { condition: Condition.TEXT_EQUALS, text: "Wool" })))).toBe(
      'screen.await(res("search")).textEquals("Wool")',
    );
    expect(describeStep(recorded(steps.wait(search, { condition: Condition.ONE })))).toBe('screen.await(res("search")).one()');
  });

  it("shows an assertion as kotlin.test around the element query", () => {
    const shown = (expect: steps.Expect) => describeStep(recorded(steps.assertion(search, expect)));
    expect(shown({ check: Check.EXISTS })).toBe('assertTrue(screen.element(res("search")).exists())');
    expect(shown({ check: Check.COUNT, count: 3 })).toBe('assertEquals(3, screen.element(res("search")).count())');
    expect(shown({ check: Check.TEXT_EQUALS, text: "Wool" })).toBe('assertEquals("Wool", screen.element(res("search")).text())');
    expect(shown({ check: Check.TEXT_CONTAINS, text: "Wo" })).toBe('assertContains(screen.element(res("search")).text().orEmpty(), "Wo")');
    expect(shown({ check: Check.DISABLED })).toBe('assertFalse(screen.element(res("search")).isEnabled())');
  });

  it("shows scroll until and the app waits as the SDK calls", () => {
    const list = steps.synthesized(selector(res("list")));
    const row = selector(match(TextProperty.PROPERTY_TEXT, "Row 40"));
    expect(describeStep(recorded(steps.scrollUntil(list, row, Direction.DIR_DOWN)))).toBe('screen.element(res("list")).scrollUntil(text("Row 40"))');
    expect(describeStep(recorded(steps.scrollUntil(list, row, Direction.DIR_UP)))).toBe('screen.element(res("list")).scrollUntil(text("Row 40"), UP)');
    expect(describeStep(recorded(steps.scrollUntil(list, row, Direction.DIR_DOWN, { distance: 50, maxScrolls: 30 })))).toBe(
      'screen.element(res("list")).scrollUntil(text("Row 40"), maxScrolls = 30, distancePercent = 50)',
    );
    expect(describeStep(recorded(steps.appWait("visible", "com.example")))).toBe('app("com.example").awaitVisible()');
    expect(describeStep(recorded(steps.appWait("stable", "com.example")))).toBe('app("com.example").awaitScreenStable()');
    expect(describeStep(recorded(steps.appWait("settled", "com.example")))).toBe('app("com.example").awaitSettled()');
    expect(describeStep(recorded(steps.appWait("animation_end", "com.example")))).toBe('app("com.example").awaitAnimationEnd()');
  });

  it("runs a selector with a package predicate on that app, and one without on the screen", () => {
    const owned = steps.synthesized(selector(all(res("search"), match(TextProperty.PROPERTY_PACKAGE_NAME, "com.example"))));
    expect(describeStep(recorded(steps.gesture(owned, "tap")))).toBe('app("com.example").element(res("search")).tap()');
    expect(describeStep(recorded(steps.wait(owned, { condition: Condition.GONE })))).toBe('app("com.example").await(res("search")).gone()');
    // Only an exact predicate in the top conjunction is the app: a nested one stays in the selector.
    const prefix = steps.synthesized(selector(all(res("a"), match(TextProperty.PROPERTY_PACKAGE_NAME, "com.", MatchMode.MATCH_STARTS_WITH))));
    expect(describeStep(recorded(steps.gesture(prefix, "tap")))).toBe(
      'screen.element(res("a").andPackageName("com.", MatchMode.STARTS_WITH)).tap()',
    );
  });

  it("shows the element actions as the SDK's Element calls", () => {
    const shown = (r: PerformRequest) => describeStep(recorded(r));
    const list = selector(res("list"));
    expect(shown(steps.gesture(search, "doubleTap"))).toBe('screen.element(res("search")).doubleTap()');
    expect(shown(steps.gesture(search, "performImeAction"))).toBe('screen.element(res("search")).imeAction()');
    expect(shown(steps.fling(search, Direction.DIR_DOWN))).toBe('screen.element(res("search")).fling(DOWN)');
    expect(shown(steps.pinch(search, true))).toBe('screen.element(res("search")).pinchOpen()');
    expect(shown(steps.pinch(search, false, 40))).toBe('screen.element(res("search")).pinchClose(40)');
    expect(shown(steps.drag(search, list))).toBe('screen.element(res("search")).dragTo(res("list"))');
    expect(shown(steps.accessibilityAction(search, { standard: StandardAction.A11Y_EXPAND }))).toBe(
      'screen.element(res("search")).performAction(StandardAction.EXPAND)',
    );
    expect(shown(steps.accessibilityAction(search, { custom: "Archive" }))).toBe('screen.element(res("search")).performCustomAction("Archive")');
    expect(shown(steps.setProgress(search, 3))).toBe('screen.element(res("search")).setProgress(3f)');
  });

  it("shows the device actions, waits, conditions and assertions as the SDK's Device calls", () => {
    const shown = (r: PerformRequest) => describeStep(recorded(r));
    expect(shown(steps.setOrientation(Orientation.LANDSCAPE))).toBe("setOrientation(Orientation.LANDSCAPE)");
    expect(shown(steps.setDisplayRotation(DisplayRotation.UPSIDE_DOWN))).toBe("setDisplayRotation(DisplayRotation.UPSIDE_DOWN)");
    expect(shown(steps.unfreezeRotation())).toBe("unfreezeRotation()");
    expect(shown(steps.pressKey(224))).toBe("wake()");
    expect(shown(steps.pressKey(223))).toBe("sleep()");
    expect(shown(steps.dismissKeyguard())).toBe("dismissKeyguard()");
    expect(shown(steps.hideKeyboard())).toBe("hideKeyboard()");
    expect(shown(steps.setClipboard("hi"))).toBe('setClipboard("hi")');
    expect(shown(steps.choosePermission(PermissionChoice.PERMISSION_ALLOW_FOREGROUND_ONLY))).toBe("choosePermission(PermissionChoice.ALLOW_FOREGROUND_ONLY)");
    expect(shown(steps.choosePermission(PermissionChoice.PERMISSION_ALLOW_ONE_TIME, LocationAccuracy.LOCATION_APPROXIMATE))).toBe(
      "choosePermission(PermissionChoice.ALLOW_ONE_TIME, LocationAccuracy.APPROXIMATE)",
    );
    expect(shown(steps.openNotification({ title: "New message", packageName: "com.example" }, "Mark as read"))).toBe(
      'openNotification(title = "New message", packageName = "com.example", action = "Mark as read")',
    );
    expect(shown(steps.dismissNotification({ text: "Ada", contains: true }))).toBe('dismissNotification(text = "Ada", mode = MatchMode.CONTAINS)');
    expect(shown(steps.awaitNotification({ packageName: "com.example" }))).toBe('awaitNotification(packageName = "com.example")');
    expect(shown(steps.awaitToast({}))).toBe("awaitToast()");
    expect(shown(steps.awaitToast({ text: "Saved", contains: true }))).toBe('awaitToast(text = "Saved", mode = MatchMode.CONTAINS)');
    expect(shown(steps.awaitPermissionPrompt())).toBe("awaitPermissionPrompt()");
    expect(shown(steps.deviceCondition({ operation: "set_animations", enabled: false }))).toBe("setAnimations(false)");
    expect(shown(steps.deviceCondition({ operation: "set_font_scale", fontScale: 1.3 }))).toBe("setFontScale(1.3f)");
    expect(shown(steps.deviceCondition({ operation: "set_density" }))).toBe("setDensity(null)");
    expect(shown(steps.deviceCondition({ operation: "set_network", wifi: false }))).toBe("setNetwork(wifi = false)");
    expect(shown(steps.deviceCondition({ operation: "set_system_locales", locales: ["fr-FR", "en-US"] }))).toBe('setSystemLocales(listOf("fr-FR", "en-US"))');
    expect(shown(steps.deviceCondition({ operation: "set_location", latitude: 51.5, longitude: 0, altitudeM: 20 }))).toBe(
      "setLocation(51.5, 0.0, altitudeM = 20.0)",
    );
    expect(shown(steps.deviceCondition({ operation: "set_accessibility_display", boldText: true }))).toBe("setAccessibilityDisplay(boldText = true)");
    expect(shown(steps.deviceAssertion(steps.DeviceCheck.FOREGROUND_ACTIVITY, "com.example/com.example.MainActivity"))).toBe(
      'assertEquals(ForegroundActivity("com.example", "com.example.MainActivity"), foregroundActivity())',
    );
    expect(shown(steps.deviceAssertion(steps.DeviceCheck.KEYBOARD_HIDDEN))).toBe("assertFalse(keyboardShown())");
    expect(shown(steps.deviceAssertion(steps.DeviceCheck.CLIPBOARD_EQUALS, "hi"))).toBe('assertEquals("hi", clipboard())');
  });

  it("shows the other app calls with their arguments", () => {
    const shown = (r: PerformRequest) => describeStep(recorded(r));
    expect(shown(steps.app("foreground", "com.example"))).toBe('app("com.example").foreground()');
    expect(shown(steps.app("revoke_permission", "com.example", { permission: "android.permission.CAMERA" }))).toBe(
      'app("com.example").revokePermission("android.permission.CAMERA")',
    );
    expect(shown(steps.app("open_link", "com.example", { uri: "app://item/1", anyApp: true }))).toBe('app("com.example").openLink("app://item/1", anyApp = true)');
    expect(shown(steps.app("open_link", "com.example", { uri: "app://item/1", anyApp: false }))).toBe('app("com.example").openLink("app://item/1")');
    expect(shown(steps.app("set_locales", "com.example", { locales: ["fr-FR"] }))).toBe('app("com.example").setLocales(listOf("fr-FR"))');
    expect(shown(steps.app("set_locales", "com.example"))).toBe('app("com.example").setLocales(listOf())');
    const extras = [
      create(IntentExtraSchema, { key: "user", value: { case: "stringValue", value: "ada" } }),
      create(IntentExtraSchema, { key: "n", value: { case: "intValue", value: 3 } }),
      create(IntentExtraSchema, { key: "id", value: { case: "longValue", value: 9n } }),
      create(IntentExtraSchema, { key: "on", value: { case: "boolValue", value: true } }),
    ];
    expect(shown(steps.app("cold_launch", "com.example", { activity: ".Main", extras }))).toBe(
      'app("com.example").coldLaunch(".Main", extras = mapOf("user" to "ada", "n" to 3, "id" to 9L, "on" to true))',
    );
  });

  it("names the step kinds", () => {
    expect(stepKind(recorded(steps.setOrientation(Orientation.PORTRAIT)))).toBe("device");
    expect(stepKind(recorded(steps.deviceCondition({ operation: "set_dark_mode", enabled: true })))).toBe("device");
    expect(stepKind(recorded(steps.awaitToast({})))).toBe("wait");
    expect(stepKind(recorded(steps.deviceAssertion(steps.DeviceCheck.KEYBOARD_SHOWN)))).toBe("assertion");
    expect(stepKind(recorded(steps.gesture(search, "doubleTap")))).toBe("action");
    expect(stepKind(recorded(steps.pressKey(4)))).toBe("key");
    expect(stepKind(recorded(steps.openSystemPanel(SystemPanel.QUICK_SETTINGS)))).toBe("system");
    expect(stepKind(recorded(steps.gesture(search, "tap")))).toBe("action");
    expect(stepKind(recorded(steps.wait(search, { condition: Condition.VISIBLE })))).toBe("wait");
    expect(stepKind(recorded(steps.assertion(search, { check: Check.EXISTS })))).toBe("assertion");
    expect(stepKind(recorded(steps.appWait("settled", "com.example")))).toBe("wait");
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
