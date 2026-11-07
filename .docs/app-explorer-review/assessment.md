# Assessment of the App Explorer review report

Assistant response to [the report](report.md), retained as design feedback—not implementation approval.

---

**It’s a strong report. I agree with its main diagnosis:** human review will be much more useful if people classify **screen families and feature roles**, rather than repair individual observations repeatedly.

The skeleton experiment is particularly valuable: it suggests we can simplify the graph considerably **without discarding the underlying evidence**. But I’d adjust several recommendations before implementing them.

### What I would adopt

- **A labeled corpus and offline metrics first.** That gives us a way to distinguish improvement from prettier grouping.
- **Screen → Variant separation.** One form can have many values and meaningful conditions without needing separate robot implementations.
- **Richer accessibility metadata.** Confirming `getError()` / `isContentInvalid()` against Loop is an excellent next investigation. “Visual-only” currently means invisible to *our captured features*, not necessarily to Android.
- **Feature-level human review.** “Is this field input, data, transient feedback or meaningful state?” is much better than repeatedly labeling screenshots.
- **Tap selectors/assertions as recognition artifacts**, where expressible, rather than inventing another selector language.
- **AI for naming and proposals**, not structural authority or input approval.

### Where I would be careful

**1. Coarse grouping must not immediately become routing identity.**

A skeleton is a useful **screen-family hypothesis**, not proof that its members behave interchangeably. Identical structures can represent different screens or different business conditions.

I would preserve three things:

> **Screen family → observable variant → execution context and parameter bindings**

The existing detailed observations remain evidence and fresh execution guards. Coarse grouping initially changes presentation and shared robot structure—not execution authority.

**2. “Input” and “data” can still affect behavior.**

Two filled numeric fields can fall on opposite sides of a validation threshold. List contents can determine whether an action exists or is ambiguous.

Value classes should therefore be **tested hypotheses**, not simply “empty versus filled.” Robot setters should accept actual values; classes guide coverage and preconditions.

Also, use `showing_hint` where available—not merely `text == hint`, since somebody could literally enter the hint text.

**3. Some causal claims are premature.**

The signature preserves traversal order and depth, but it does **not** hash bounds or focus flags. Keyboard structure can affect it; scroll changes can affect visible structure.

Oversplitting probably contributes to exploration cost, but the report hasn’t established that it *explains* the 18-minute run. Captures, device waits and routing also need profiling.

**4. Assertion discrimination is useful, but not universally required.**

An assertion that distinguishes sibling variants is valuable. However, shared invariants—correct package, required controls present, reminders disabled—can legitimately pass across every observed variant.

Passing stored observations is also **offline validation**, not proof of future correctness. Fresh native checks remain necessary.

**5. Ancestor selectors won’t solve every custom control.**

They look promising for clickable filter rows. They won’t help if a custom gesture-handling view exposes **no clickable ancestor**. We must inspect the actual hierarchy rather than assume one exists.

### My recommended order

1. Build the labeled corpus and compare policies offline.
2. Confirm and capture missing accessibility signals.
3. Add screen-family grouping **without changing routing identity**.
4. Add feature-role review and scoped recognition proposals.
5. Validate refinements through fresh trials.
6. Generate robots and tests only from verified contracts.

**Bottom line:** this is a better direction than observation-by-observation correction. I would adopt it, with a firm distinction between **useful abstraction** and **proven execution equivalence**. That distinction is what keeps simplification from becoming unsafe merging.
