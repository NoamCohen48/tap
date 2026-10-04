# Selectors

!!! info "Examples are in Python"
    Kotlin has the same builders in camelCase (`class_name` → `className`, `and_hint` →
    `andHint`, `&` / `|` → `and` / `or`). [Kotlin + JUnit 5](../sdk/kotlin.md#selectors) shows
    this page's examples in Kotlin.

A selector describes *which* node of the accessibility tree you mean. It is a small, immutable
value: no I/O happens when you build one. `app.element(selector)` (for
`app = device.app("com.shop")`) or `device.screen.element(selector)` wraps it in an `Element`
whose every action resolves the selector again on the device, so there is nothing to go stale.

```python
from tap_e2e import res, text, class_name

app = device.app("com.shop")
app.element(res("buy_button")).tap()             # com.shop:id/buy_button
app.element(text("Add to cart").clickable()).tap()
app.element(class_name("android.widget.EditText").and_hint("Search")).set_text("socks")
```

Where the selector looks, one app's nodes or the whole screen, is chosen by what you call
`element` on, not by the selector: see [App or screen](app-or-screen.md).

## Entry points

| Builder | Matches |
|---|---|
| `res(name)` | resource id `name` in any package: a View id `<pkg>:id/name`, or a Compose `testTag` `name` when the app sets `testTagsAsResourceId`. Under `app.element` only that app's nodes match, so this is the app's own id |
| `res_id(pkg, name)` | exactly the View id `pkg:id/name`, for an id a library or the system defines under another package (`res_id("android", "button1")`) |
| `text(value)` | exact text |
| `text_contains(v)`, `text_starts_with(v)` | substring / prefix |
| `text_matches(re2)` | RE2 regular expression (full match) |
| `desc(value)` | content description |
| `hint(value)` | hint of an empty text field |
| `class_name(value)` | widget class name |
| `clickable()`, `scrollable()` | any node with that property |

`text`, `desc`, `hint` and `class_name` take a `MatchMode` (`EXACT`, `CONTAINS`,
`STARTS_WITH`, `ENDS_WITH`, `REGEX`, also exported as plain constants). A name passed to
`res` / `and_res` is the bare name: `res("pkg:id/name")` is rejected
(`QUALIFIED_RESOURCE_NAME`); write `res_id("pkg", "name")`. Regexes are RE2: linear-time, no
backreferences or lookaround, and an invalid pattern is rejected before it reaches the device.

!!! note "Text vs hint"
    An empty `EditText` reports its hint as its accessibility text. Tap passes that through
    unchanged: text selectors match it and `element.text()` returns it, while
    `snapshot().showing_hint` says the text is the hint. Use `hint(...)` to find an empty field
    by its hint.

## Refinements

Everything after the entry point narrows the same node:

- `.and_text(v)`, `.and_desc(v)`, `.and_hint(v)`, `.and_class_name(v)`, `.and_res(name)`,
  `.and_res(pkg, name)`
- `.clickable()`, `.long_clickable()`, `.scrollable()`, `.checkable()`, `.checked()`,
  `.enabled()`, `.focusable()`, `.focused()`, `.selected()`, each with an optional `bool`

```python
text("Remember me").checkable().checked(False)   # an unchecked checkbox labelled "Remember me"
```

## Combining selectors

Two selectors combine into one with `&` (and) and `|` (or), or with `all_of(...)` /
`any_of(...)` for a list. The result is still one selector describing one node: `exists()`,
`count()`, the exactly-one rule and every action apply to it as a whole.

```python
# the permission button, whichever wording this Android version uses
allow = text("Allow") | text("Allow only while using the app") | text("While using the app")
device.screen.element(allow).tap()

# both on the same node
app.element(text("Add") & clickable()).tap()     # same as text("Add").clickable()
```

Combinators nest freely (`(a | b) & clickable()`), including inside relations
(`has_child(a | b)`). The result keeps the left-hand operand's `first()` / `at(n)`. Nothing is
dropped silently: a right-hand operand (of `&` / `|` or a `has_*` relation) that carries its
own `first()` / `at(n)` is rejected with `ValueError`; apply it to the combined selector instead.

A selector with an or is evaluated by walking the window's node tree rather than by a native
UiAutomator lookup. It returns exactly the same matches and never dumps the hierarchy, but on a
very large screen it is a little slower: prefer a single distinguishing property when one
exists.

## Relations

Two flavours. **Constrain by relatives** keeps matching the outer node:

| Method | The matched node… |
|---|---|
| `.has_descendant(s)` | has some descendant matching `s` |
| `.has_child(s)` | has a direct child matching `s` |
| `.has_parent(s)` | has a parent matching `s` |
| `.has_ancestor(s)` | has an ancestor matching `s` |

**Navigate to relatives** returns the inner node:

| Method | Returns |
|---|---|
| `.descendant(s)` | the node matching `s` somewhere below |
| `.child(s)` | the direct child matching `s` |

```python
# the "Delete" button inside the row whose title is "Socks"
row = class_name("android.view.ViewGroup").has_descendant(text("Socks"))
app.element(row.descendant(text("Delete"))).tap()
```

`Element.descendant(...)` and `Element.child(...)` exist too, so you can navigate from an
`Element` you already hold. The result keeps the inner selector's `first()` / `at(n)`. The outer
selector itself cannot carry `first()` / `at(n)`: `rows.at(2).descendant(x)` would otherwise
search under every row, so it is rejected. Narrow the outer node with properties, or pick among
the results (`rows.descendant(x).at(2)`).

## Exactly one, or say otherwise

By default a selector must match **exactly one** node for any action (`tap`, `set_text`,
`swipe`, scroll containers, `snapshot`). Two matches fail with `AMBIGUOUS`, none with
`NOT_FOUND`, and in both cases nothing was touched. `exists()` and `count()` do not care.

When a repeated element is genuinely what you want, opt in explicitly:

```python
app.element(text("Add").first()).tap()          # first in accessibility order
app.element(class_name("Button").at(2)).tap()   # third in accessibility order
```

Accessibility order is stable for a given screen but is not guaranteed to be visual order;
prefer a distinguishing relation (`has_descendant(text(...))`) when there is one.

## Limits and what is not supported

- Depth ≤ 32, ≤ 256 nodes, ≤ 1024 characters per string; an and / or needs at least two
  operands (the builders never produce fewer). Violations are `INVALID_SELECTOR` with a stable
  detail, raised before a request is sent.
- **No NOT, sibling or "nearest".** A negative or positional selector tends to match something
  unintended when the screen changes; describe the node you want instead.
- **No XPath and no string query language.** Selectors are an AST validated identically by the
  client, the server and the driver, and compiled on the device to window-scoped
  UiAutomator lookups. This is what keeps lookups fast and error messages precise.
- **No element handles.** There is no `find_element()` returning an id to reuse; an `Element`
  is the selector plus the device. Re-resolving is cheap (one round trip, no hierarchy dump)
  and removes stale-element errors as a class.
- **No coordinates.** Taps and gestures are always relative to a matched element. If you need
  a raw point, that is a sign the accessibility tree is missing something worth fixing in the
  app.

## Debugging a selector

- `selector.render()` (or `str(selector)`) prints the exact expression tree the device will
  see; it also appears in every `CommandError`.
- `app.element(sel).count()` tells you how many nodes match right now.
- `device.dump_hierarchy()` returns the accessibility XML for the current screen (diagnostic
  only, never used by lookups). The [failure artifacts](artifacts.md#failure-artifacts) contain
  the same dump, and [Tap Studio](../studio/index.md) shows every node's selectors on a live
  screen.
