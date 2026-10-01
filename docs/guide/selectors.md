# Selectors

A selector describes *which* node of the accessibility tree you mean. It is a small, immutable
value — no I/O happens when you build one, so builders stay non-`suspend` and work outside
`tapTest`/`tapScope`. `app.element(selector)` (for `app = device.app("com.shop")`) or
`device.screen.element(selector)` wraps it in an `Element` whose every action resolves the
selector again on the device, so there is nothing to go stale.

=== "Kotlin"

    ```kotlin
    import io.github.noamcohen48.tap.sdk.*

    val app = device.app("com.shop")
    app.element(res("buy_button")).tap()             // com.shop:id/buy_button
    app.element(text("Add to cart").clickable()).tap()
    app.element(className("android.widget.EditText").andHint("Search")).setText("socks")
    ```

=== "Python"

    ```python
    from tap_e2e import res, text, class_name

    app = device.app("com.shop")
    app.element(res("buy_button")).tap()             # com.shop:id/buy_button
    app.element(text("Add to cart").clickable()).tap()
    app.element(class_name("android.widget.EditText").and_hint("Search")).set_text("socks")
    ```

Where the selector looks — one app's nodes, or the whole screen — is chosen by what you call
`element` on, not by the selector; see [App or screen](#app-or-screen).

## Entry points

| Kotlin | Python | Matches |
|---|---|---|
| `res(name)` | `res(name)` | resource id `name` in any package — a View id `<pkg>:id/name`, or a Compose `testTag` `name` when the app sets `testTagsAsResourceId`. Under `app.element` only that app's nodes match, so this is the app's own id |
| `resId(pkg, name)` | `res_id(pkg, name)` | exactly the View id `pkg:id/name` — for an id a library or the system defines under another package (`resId("android", "button1")`) |
| `text(value)` | `text(value)` | exact text |
| `textContains(v)`, `textStartsWith(v)` | `text_contains`, `text_starts_with` | substring / prefix |
| `textMatches(re2)` | `text_matches(re2)` | RE2 regular expression (full match) |
| `desc(value)` | `desc(value)` | content description |
| `hint(value)` | `hint(value)` | hint of an empty text field |
| `className(value)` | `class_name(value)` | widget class name |
| `clickable()`, `scrollable()` | `clickable()`, `scrollable()` | any node with that property |

`text`, `desc`, `hint` and `className` take a `MatchMode` (`EXACT`, `CONTAINS`, `STARTS_WITH`,
`ENDS_WITH`, `REGEX`; Python also exports them as plain constants). A name passed to
`res`/`andRes` is the bare name: `res("pkg:id/name")` is rejected (`QUALIFIED_RESOURCE_NAME`);
write `resId("pkg", "name")`.
Regexes are RE2: linear-time, no backreferences or lookaround, and an invalid pattern is
rejected before it reaches the device.

!!! note "Text vs hint"
    An empty `EditText` reports its hint as its accessibility text. Tap passes that through
    unchanged: text selectors match it and `element.text()` returns it, while
    `snapshot().showingHint` (`showing_hint`) says the text is the hint. Use `hint(...)` to
    find an empty field by its hint.

## Refinements

Everything after the entry point narrows the same node:

| Kotlin | Python |
|---|---|
| `.andText(v)`, `.andDesc(v)`, `.andHint(v)`, `.andClassName(v)`, `.andRes(name)`, `.andRes(pkg, name)` | `.and_text`, `.and_desc`, `.and_hint`, `.and_class_name`, `.and_res(name)`, `.and_res(pkg, name)` |
| `.clickable()`, `.longClickable()`, `.scrollable()`, `.checkable()`, `.checked()`, `.enabled()`, `.focusable()`, `.focused()`, `.selected()` — each takes an optional `Boolean` | same, snake_case |

```kotlin
text("Remember me").checkable().checked(false)   // an unchecked checkbox labelled "Remember me"
```

## Combining selectors

Two selectors combine into one with **and** / **or** (Kotlin infix `and` / `or`, Python
`&` / `|`), or with `allOf(...)` / `anyOf(...)` (`all_of` / `any_of`) for a list. The result is
still one selector describing one node: `exists()`, `count()`, the exactly-one rule and every
action apply to it as a whole.

=== "Kotlin"

    ```kotlin
    // the permission button, whichever wording this Android version uses
    val allow = text("Allow") or text("Allow only while using the app") or text("While using the app")
    device.screen.element(allow).tap()

    // both on the same node
    app.element(text("Add") and clickable()).tap()             // same as text("Add").clickable()
    ```

=== "Python"

    ```python
    allow = text("Allow") | text("Allow only while using the app") | text("While using the app")
    device.screen.element(allow).tap()

    app.element(text("Add") & clickable()).tap()
    ```

Combinators nest freely (`(a or b) and clickable()`), including inside relations
(`hasChild(a or b)`). The result keeps the left-hand operand's `first()`/`at(n)`.
Nothing is dropped silently: a right-hand operand (of `and`/`or` or a `has*` relation) that
carries its own `first()`/`at(n)` is rejected (`IllegalArgumentException` / `ValueError`) —
apply it to the combined selector instead.
A selector with an `or` is evaluated by walking the window's node tree rather than by a
native UiAutomator lookup; it returns exactly the same matches and never dumps the
hierarchy, but on a very large screen it is a little slower — prefer a single distinguishing
property when one exists.

## Relations

Two flavours. **Constrain by relatives** keeps matching the outer node:

| Kotlin | Python | The matched node… |
|---|---|---|
| `.hasDescendant(s)` | `.has_descendant(s)` | has some descendant matching `s` |
| `.hasChild(s)` | `.has_child(s)` | has a direct child matching `s` |
| `.hasParent(s)` | `.has_parent(s)` | has a parent matching `s` |
| `.hasAncestor(s)` | `.has_ancestor(s)` | has an ancestor matching `s` |

**Navigate to relatives** returns the inner node:

| Kotlin | Python | Returns |
|---|---|---|
| `.descendant(s)` | `.descendant(s)` | the node matching `s` somewhere below |
| `.child(s)` | `.child(s)` | the direct child matching `s` |

```kotlin
// the "Delete" button inside the row whose title is "Socks"
val row = className("android.view.ViewGroup").hasDescendant(text("Socks"))
app.element(row.descendant(text("Delete"))).tap()
```

Both `Element.descendant(...)`/`Element.child(...)` and the selector methods exist, so you can
navigate from an `Element` you already hold. The result keeps the inner selector's
`first()`/`at(n)`. The outer selector itself cannot carry
`first()`/`at(n)` — `list.at(2).descendant(x)` would otherwise search under every list — so it is
rejected; narrow the outer node with properties, or pick among the results
(`list.descendant(x).at(2)`).

## Exactly one, or say otherwise

By default a selector must match **exactly one** node for any action (`tap`, `setText`,
`swipe`, scroll containers, `snapshot`). Two matches fail with `AMBIGUOUS`, none with
`NOT_FOUND`, and in both cases nothing was touched. `exists()` and `count()` do not care.

When a repeated element is genuinely what you want, opt in explicitly:

```kotlin
app.element(text("Add").first()).tap()      // first in accessibility order
app.element(className("Button").at(2)).tap() // third in accessibility order
```

Accessibility order is stable for a given screen but is not guaranteed to be visual order;
prefer a distinguishing relation (`hasDescendant(text(...))`) when there is one.

## App or screen

Every lookup searches all the windows on screen. What you call `element` / `await` on decides
which of their nodes count:

- `device.app(pkg).element(sel)` / `.await(sel)` (Python `.wait`): only nodes that belong to
  `pkg`. The package is one more predicate in the selector the device receives, so a
  dialog, a keyboard or a notification of another package can never be what a test of your
  app matches, whether or not it is focused.
- `device.screen.element(sel)` / `.await(sel)`: nodes of any package — system dialogs, the
  status bar's panels, the launcher. Use it when you do not know, or do not care, which
  package owns what you are after.

Attaching a device names no app: one test can drive several apps (`device.app("a")`,
`device.app("b")`) and the system UI (`device.screen`) on the same device.

A permission dialog, for instance:

=== "Kotlin"

    ```kotlin
    device.app("com.google.android.permissioncontroller")
        .element(resId("com.android.permissioncontroller", "permission_allow_button"))
        .tap()
    ```

=== "Python"

    ```python
    device.app("com.google.android.permissioncontroller").element(
        res_id("com.android.permissioncontroller", "permission_allow_button")
    ).tap()
    ```

For tests that just need the permission, prefer `app.grantPermission(...)`, which needs no
dialog at all.

Another window — a keyboard, a popup, another app's overlay — can hide a node you match:

- Covered completely, the node is not found: Android reports it as not visible, so an action
  fails with `NOT_FOUND` and `visible()` keeps waiting.
- Covered partly, it is found, but a gesture whose touch point (a tap's centre, a swipe's
  start) is under the other window fails with `NOT_INTERACTABLE` / `OBSCURED` before any input,
  instead of landing on whatever is on top.

Close the covering window (often `pressBack()`) or scroll the node clear first.

## Limits and what is not supported

- Depth ≤ 32, ≤ 256 nodes, ≤ 1024 characters per string; an `and`/`or` needs at least two
  operands (the builders never produce fewer). Violations are `INVALID_SELECTOR` with a stable
  detail, raised before a request is sent.
- **No NOT, sibling or "nearest".** A negative or positional selector tends to match something
  unintended when the screen changes; describe the node you want instead.
- **No XPath and no string query language.** Selectors are an AST validated identically by the
  client, the server and the driver, and compiled on the device to window-scoped
  UiAutomator lookups. This is what keeps lookups fast and error messages precise.
- **No element handles.** There is no `findElement()` returning an id to reuse; an `Element`
  is the selector plus the device. Re-resolving is cheap (one round trip, no hierarchy dump)
  and removes stale-element errors as a class.
- **No coordinates.** Taps and gestures are always relative to a matched element. If you need
  a raw point, that is a sign the accessibility tree is missing something worth fixing in the
  app.

## Debugging a selector

- `selector.render()` (or `str(selector)`) prints the exact expression tree the device will
  see; it also appears in every `CommandException`/`CommandError`.
- `app.element(sel).count()` tells you how many nodes match right now.
- `device.dumpHierarchy()` returns the accessibility XML for the current screen (diagnostic
  only — never used by lookups). The failure artifacts contain the same dump.
