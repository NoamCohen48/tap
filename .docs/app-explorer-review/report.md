# App Explorer review report

User-provided report, retained as a proposal and analysis—not an implementation or independently verified result.

---

I read both design docs, discovery.py, session.py, ai.py and live.py, and checked a few ideas against your saved runs in .tap/explorer/. The human-review doc gets the trust boundaries right: reviewing a classification never approves input, history is append-only and rules stay provisional. My main concern is the heuristic underneath it. It has one level of identity, set at the finest grain, so humans end up fixing the wrong things one observation at a time.

What your own runs show

I tried a crude "screen skeleton" key on the saved graphs: the set of (resource ID, class) pairs, ignoring text, flags, order and repeats.

- Wizard (native-machine-run-5): 30 states became 7 skeletons, sized 12/5/5/3/3/1/1. That matches the page/variant counts in §23 exactly, so a very simple key already recovers the layer your robots need.
- Loop (real-app-loop/run-2): 24 states became 8 skeletons. The biggest group, 9 states of the Measurable form, differs only in text you typed (nameInput, unitInput, targetInput) and in picker values. Those 9 states are one robot with field values. The 4 list states differ only by habit names and the empty-list message, which is data.

The current signature (discovery.py:41-53) hashes every text, depth and node position in one digest. User input, list data, scroll position and whether the keyboard is showing each create a new state. That explains both the oversplitting and the 18-minute wizard run.

1. Two levels of identity: Screen → Variant

- Screen key (one robot each): a structural skeleton with list rows collapsed, plus the window/pane title and foreground activity once they're exposed (see §2).
- Variant: what differs inside a screen, such as an error showing, a toggle on, or a field empty vs. filled.

Start coarse and split a screen only when the evidence requires it, which is what APE does ("Practical GUI testing of Android apps via model abstraction and refinement", ICSE 2019). The trigger is that the same (screen, action) leads to different outcomes, or a human says "this matters". You currently start at the finest grain and ask humans to merge, which is the expensive direction. You keep raw features for every observation, so each policy can be recomputed offline and compared.

2. Fix the observation gaps in the snapshot first

Your Loop "visual-only" error is very likely not visual-only. I believe Loop sets nameInput.error (TextView setError), and Android then reports it through AccessibilityNodeInfo.getError() / isContentInvalid(). ScreenNode (device.proto:187) doesn't carry those fields. I haven't confirmed this against Loop's source or a device. If it holds, these additive snapshot fields would turn some human-review items into deterministic signals:

┌───────────────────────────────┬──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│             Field             │                                                            Fixes                                                             │
├───────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ error, content_invalid        │ Validation variants (Loop gap 1)                                                                                             │
├───────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ state_description, pane_title │ Compose/fragment state and screen titles                                                                                     │
├───────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Window type, layer, title     │ Toast vs. dialog vs. IME, decided without guessing (gap 2)                                                                   │
├───────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ input_type, editable          │ Fill detection without matching class names ending in EditText (discovery.py:110) and choosing value classes (number, email) │
├───────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Foreground activity           │ A strong screen-key signal                                                                                                   │
└───────────────────────────────┴──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

For changes that really are visual-only, compare the before/after screenshots of the app window only. Mask the status bar and the focused field's cursor. Use it only to flag an item for review, never to decide identity. You have about 470 saved pairs, enough to measure its false-alarm rate before relying on it.

3. Give each feature a role, inferred from evidence

Inside a screen, sort every slot (resource + class) into a role. Most of these can be inferred automatically:

- Static label: the same in every observation of the screen.
- Input echo: equals a value you filled, which is already in the attempt record. Reduce it to a value class (empty / filled / scenario class). Note that an empty EditText reports its hint as text ("e.g. Run"), so treat text == hint as empty.
- Data: inside a repeated container (list rows). It doesn't affect identity, but it becomes a robot parameter.
- Volatile: changes with no action. Find these by taking two snapshots about a second apart while idle, then propose them for volatile_resources instead of curating that list by hand.
- State-bearing: flags, errors, enabled state, picker values. These define variants.

This also fixes the review queue. Instead of asking "classify obs-000123", ask "these 6 states differ only in targetInput text: input, data, volatile, or meaningful?" One answer then covers every past and future observation and can be checked offline. Order the queue by how many states or edges each answer would merge or split.

4. Make recognition rules Tap selectors

The human-review doc's step 3 ("how can we recognize this again?") should produce a Tap selector/assertion, not a separate rule language. Then:

- The explorer can propose the rule itself: find a small set of features present in every member of a screen or variant and absent from every other one. Prefer resource IDs, then static text. Check it against every stored observation, which is the validation step in your doc's step 4.
- A validated screen rule is the robot's verify(), and a validated variant rule is an expectX() method. The classifier and the generated code share one artifact, so they can't drift apart.

5. Robot shape, from deterministic effect types

Classify each transition from the feature diff:

┌─────────────────────────────────┬────────────────────────────────────────────────────────────┐
│             Effect              │                        Robot method                        │
├─────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ Different skeleton              │ Navigation method returning the destination screen's robot │
├─────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ New dialog/window layer         │ Method returning a dialog robot                            │
├─────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ Same skeleton, variant changed  │ Returns this robot; the diff becomes a candidate assertion │
├─────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ No change (features and pixels) │ No-op; deprioritize, and flag focus-only taps              │
├─────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ Fill                            │ Field setter; the parameter is the value class             │
└─────────────────────────────────┴────────────────────────────────────────────────────────────┘

Then:

- Workflows: extract repeated "fill… then submit" patterns from verified routes (e.g. createMeasurableHabit(name, unit, target)). If the outcome depends on the input class, generate one method per outcome (submit() / submitExpectingBlankNameError()), not a method that branches.
- List rows (Loop gap 3): when a label isn't clickable, propose its nearest clickable ancestor, selected as "clickable ancestor that contains text X" using Tap's existing ANCESTOR/DESCENDANT relations. That gives openHabit(name) with no index picks. Use the same approach for checkboxes inside clickable rows.
- Selector quality across observations: keep a selector for the robot only if it is unique in every saved observation of its screen. Flag text-based selectors whose text varies between observations.

6. Assertions and generated tests

- Propose assertions from observed diffs, and keep only ones that would fail in at least one other observed state. An assertion that passes everywhere checks nothing.
- A human promotes an assertion from "observed" to "business". The explorer never does.
- Generate one test per verified route or variant, each from the starting condition. A test is accepted only after it compiles and passes N fresh runs, and its assertions fail when replayed against a sibling variant. That last check catches tests that pass whatever the app does.

7. Hidden state

When the same (source, action) has conflicting outcomes, compare the action histories since the start of the run for each outcome. Propose the action that separates them as a hypothesis ("outcome depends on whether Save was pressed with a blank name"). A human confirms, and the screen then splits on that context. This makes the doc's "History-dependent" choice a proposal backed by evidence rather than a bare label.


8. Where the AI is worth paying for

AI is good at naming and judging; deterministic code should decide structure. Use it to:

- Name screens, methods and parameters for already-computed skeletons and diffs.
- Pre-fill review-queue answers for a human to confirm.
- Compare the two screenshots only on items flagged by §2.
- Suggest field semantics and value classes.

Measure how often the AI agrees with human answers on the labeled corpus (§9). Choose the model by that measurement, keeping nano only if it's accurate enough. Seeing a red error icon at low image detail is exactly the case where a nano model is likely to fail.

9. Do this first: a labeled corpus and metrics

You already have the data: the wizard oracle (30 states known exactly), the Loop runs with their screenshots, and the 14 Loop checks. Turn them into an offline benchmark that scores each signature/policy version on:

- false merges and false splits, at both screen and variant level;
- how often rules mistake one screen for another;
- selector stability;
- how often assertions would fail on a sibling state.

Every answer in the review queue becomes another labeled case. Without this, each heuristic change is judged by eye.

Specific edits to app-explorer-human-review.md

- Add "Data variant" (same screen, different content) and "Different screen" to the classification table.
- Let review answers apply to a feature or slot, not only to one observation.
- State that recognition rules are Tap selectors/assertions.
- "Temporary overlay" and the first half of "History-dependent" can become deterministic proposals once the snapshot fields and the history comparison exist.
- Delivery order: corpus and metrics, then the snapshot fields, then Screen/Variant and feature roles, then auto-proposed rules and the review queue, then robot operations, then test generation.

Everything I measured was read-only analysis of saved runs; no device or AI calls were made. I can write this up as a section in .docs/app-explorer.md, or start with the corpus/benchmark script. The skeleton experiment above is already a small version of it.
