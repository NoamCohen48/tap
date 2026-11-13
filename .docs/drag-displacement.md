# Drag by displacement

Status: proposed, not implemented. API names and details below are suggestions for discussion,
not a supported contract or an approved implementation plan.

## Motivation

Tap currently drags from one element to another with `drag_to` / `dragTo`. That cannot express
a drop into empty space without a destination element. Keep the source selector-based, but
allow a displacement from its visible centre without requiring an absolute pixel location.

The displacement should not depend on the source element's width or height: moving a small
icon across a board should not require expressing the distance in icon widths.

## Suggested units

Offer screen percentages for movement that adapts to screen size, and density-independent
pixels (dp) for small, controlled adjustments. Neither requires absolute screen coordinates.

### Screen percentages

Illustrative Python API:

```python
card.drag_by(right_percent=25, down_percent=10)
card.drag_by(right_percent=-25)  # move left
```

Start at the card's visible centre. Move right by 25% of the current screen width and down by
10% of the current screen height, then release. Negative values move left or up; an omitted
axis means zero movement on that axis.

These values describe **displacement**, not an absolute destination at 25% across and 10%
down the screen. They are percentages (`25` means 25%), not fractions (`0.25`). The card's
size does not affect the distance.

### Density-independent pixels

```python
card.drag_by(right_dp=100)
card.drag_by(right_dp=100, down_dp=-40)
```

Move by a fixed logical UI distance, converted using the device's current display density.
Android's conversion is `pixels = dp × densityDpi / 160`. This adapts to density, but not
screen size, and does not guarantee the same physical distance on every device.

### Why these units

| Unit | Useful for | Trade-off |
|---|---|---|
| Screen percentage | Moving across a substantial part of the display | Distance changes with screen size and orientation |
| dp | Small, controlled movement | May not fit on a smaller display |
| Raw pixels | Exact device-specific input | Fragile across densities and resolutions; not proposed |
| Named distances (`short`, `medium`, `long`) | Readable commands | Arbitrary thresholds hide the actual movement; not proposed |
| Source-element proportions | Movement tied to an element's dimensions | Small sources make large movements awkward; not proposed |

## Proposed behavior and safeguards

- Keep existing element-to-element `drag_to` / `dragTo` unchanged.
- Resolve the source to exactly one match before input. Preserve `NOT_FOUND`, `AMBIGUOUS`
  and existing covered-touch-point checks.
- Use the existing drag gesture semantics: long press, move, briefly hold at the destination,
  release. This is not a swipe or a teleport.
- Use one unit family per call: percentages or dp, never a mixture. Reject non-finite values,
  calls with no displacement, and a zero net displacement before input.
- Calculate and validate the destination before pressing down. Reject off-screen destinations
  rather than silently clamping them. Preserve applicable gesture safety checks; adding
  displacement must not bypass existing coverage protections.
- Continue reporting input acceptance, not whether the app accepted the drop. Tests must assert
  the resulting app state separately. Never retry a transmitted mutation automatically.

## Details still to decide

- Whether screen percentages use the full active display or its usable content area excluding
  system bars and cutouts. Document one definition consistently across clients and orientations.
- Final API spelling, including Kotlin equivalents and whether explicit displacement types are
  preferable to keyword arguments. Examples here illustrate behavior, not final signatures.
- Rounding from percentages/dp to pixels, handling a nonzero displacement that rounds to zero,
  and any bounds on accepted magnitudes.
- Exact destination coverage validation and error mapping, including overlays and display edges.
- How agent CLI/MCP and Tap Studio expose the units without suggesting absolute coordinates.

## Implementation and verification scope

A future implementation needs a protocol operation that cannot be mistaken for the existing
selector-to-selector drag, driver conversion and validation, and corresponding client APIs.
Update the internal protocol/server records and public guides in the same implementation
change; regenerate committed stubs when the schema changes.

Regression tests should cover unit conversion, both axes and negative offsets, mixed-unit and
invalid-value rejection, missing/ambiguous sources with no injected input, destination bounds,
coverage safeguards, and unchanged element-to-element dragging. Device validation, when
explicitly requested, should exercise dragging into empty space, read back the resulting app
state, and cover orientation and display density differences.

No implementation, protocol changes, or device validation are included in this proposal.
