# Selectors

A selector describes *which* node of the accessibility tree you mean. It is a small, immutable
value — no I/O happens when you build one, so builders stay non-`suspend` and work outside
`tapTest`/`tapScope`. `device.element(selector)` wraps it in an `Element`
whose every action resolves the selector again on the device, so there is nothing to go stale.

=== "Kotlin"

    ```kotlin
    import io.github.noamcohen48.tap.sdk.*

    device.element(res("buy_button")).tap()          // <aut>:id/buy_button
    device.element(text("Add to cart").clickable()).tap()
    device.element(className("android.widget.EditText").andHint("Search")).setText("socks")
    ```

=== "Python"

    ```python
    from tap_e2e import res, text, class_name

    device.element(res("buy_button")).tap()          # <aut>:id/buy_button
    device.element(text("Add to cart").clickable()).tap()
    device.element(class_name("android.widget.EditText").and_hint("Search")).set_text("socks")
    ```

## Entry points

| Kotlin | Python | Matches |
|---|---|---|
| `res(name)` | `res(name)` | View resource id `name` of the **app under test** — `<aut>:id/name`, with the package filled in by the server from the session, so the same selector works on every device and role |
| `resId(pkg, name)` | `res_id(pkg, name)` | resource id `pkg:id/name` in an explicit package (a system dialog, another app) |
| `rawRes(name)` | `raw_res(name)` | the exact, unqualified resource name — a Compose `testTag` when the app sets `testTagsAsResourceId`. It does **not** match View ids, whose name is always `pkg:id/name` |
| `text(value)` | `text(value)` | exact text |
| `textContains(v)`, `textStartsWith(v)` | `text_contains`, `text_starts_with` | substring / prefix |
| `textMatches(re2)` | `text_matches(re2)` | RE2 regular expression (full match) |
| `desc(value)` | `desc(value)` | content description |
| `hint(value)` | `hint(value)` | hint of an empty text field |
| `className(value)` | `class_name(value)` | widget class name |
| `clickable()`, `scrollable()` | `clickable()`, `scrollable()` | any node with that property |

`text`, `desc`, `hint` and `className` take a match mode (`MATCH_EXACT`, `MATCH_CONTAINS`,
`MATCH_STARTS_WITH`, `MATCH_ENDS_WITH`, `MATCH_REGEX`; in Python `EXACT`, `CONTAINS`, …).
Regexes are RE2: linear-time, no backreferences or lookaround, and an invalid pattern is
rejected before it reaches the device.

!!! note "Text vs hint"
    An empty `EditText` reports its hint as its accessibility text. Tap's text *observations*
    (`element.text()`, the verification behind `setText`) treat that as empty text, but text
    *selectors* match what UiAutomator sees. Use `hint(...)` to find an empty field.

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
    device.element(allow.inSystemPackage("com.google.android.permissioncontroller")).tap()

    // both on the same node
    device.element(text("Add") and clickable()).tap()          // same as text("Add").clickable()
    ```

=== "Python"

    ```python
    allow = text("Allow") | text("Allow only while using the app") | text("While using the app")
    device.element(allow.in_system_package("com.google.android.permissioncontroller")).tap()

    device.element(text("Add") & clickable()).tap()
    ```

Combinators nest freely (`(a or b) and clickable()`), including inside relations
(`hasChild(a or b)`). The result keeps the left-hand operand's scope and `first()`/`at(n)`.
Nothing is dropped silently: a right-hand operand (of `and`/`or` or a `has*` relation) that
carries its own `first()`/`at(n)`, or a different `inSystemPackage(...)`, is rejected
(`IllegalArgumentException` / `ValueError`) — apply them to the combined selector instead.
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
device.element(row.descendant(text("Delete"))).tap()
```

Both `Element.descendant(...)`/`Element.child(...)` and the selector methods exist, so you can
navigate from an `Element` you already hold. The result keeps the inner selector's
`first()`/`at(n)` and the outer one's scope. The outer selector itself cannot carry
`first()`/`at(n)` — `list.at(2).descendant(x)` would otherwise search under every list — so it is
rejected; narrow the outer node with properties, or pick among the results
(`list.descendant(x).at(2)`).

## Exactly one, or say otherwise

By default a selector must match **exactly one** node for any action (`tap`, `setText`,
`swipe`, scroll containers, `snapshot`). Two matches fail with `AMBIGUOUS`, none with
`NOT_FOUND`, and in both cases nothing was touched. `exists()` and `count()` do not care.

When a repeated element is genuinely what you want, opt in explicitly:

```kotlin
device.element(text("Add").first()).tap()      // first in accessibility order
device.element(className("Button").at(2)).tap() // third in accessibility order
```

Accessibility order is stable for a given screen but is not guaranteed to be visual order;
prefer a distinguishing relation (`hasDescendant(text(...))`) when there is one.

## Scope

Selectors are scoped to the **app under test**: they match only inside the focused window of
that package. A system dialog, the launcher, or a notification shade cannot be hit by
accident, and a selector that names another package's resource is rejected
(`SCOPE_DENIED`).

The one sanctioned exception is the runtime-permission dialog. The driver keeps an allowlist
of system packages (by default `com.google.android.permissioncontroller`); a selector opts in
per use:

=== "Kotlin"

    ```kotlin
    device.element(
        resId("com.android.permissioncontroller", "permission_allow_button")
            .inSystemPackage("com.google.android.permissioncontroller"),
    ).tap()
    ```

=== "Python"

    ```python
    device.element(
        res_id("com.android.permissioncontroller", "permission_allow_button")
        .in_system_package("com.google.android.permissioncontroller")
    ).tap()
    ```

Any other package is `SCOPE_DENIED`. (For tests that just need the permission, prefer
`app.grantPermission(...)`, which needs no dialog at all.)

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
- `device.element(sel).count()` tells you how many nodes match right now.
- `device.dumpHierarchy()` returns the accessibility XML for the current screen (diagnostic
  only — never used by lookups). The failure artifacts contain the same dump.
