# Offline discovery corpus and similarity benchmark

This evidence-only scorer changes **no live identities, graph routes or approvals** and makes
no device/model calls. It uses only the standard library, without the `live` extra.

```bash
tap-explorer benchmark --corpus clients/explorer/benchmarks/corpus-v2.json
# Candidate, with a failing exit status for any labeled screen merge:
tap-explorer benchmark --corpus clients/explorer/benchmarks/corpus-v2.json \
  --policy similarity --require-no-screen-merges
```

## Corpus v2: evidence, not human ground truth

`tap-discovery-corpus/2` contains **114 observations** with original retained features and
complete **owned-package snapshot nodes** (bounds, depth, flags, native selector proposals):

- Wizard: first/last capture of each of 30 states; 60 cases, seven screen families and 30
  literal variants from the fixture's finite source-defined oracle.
- Loop: all 54 captures; eight foreground families and 21 literal observable conditions with
  **provisional analyst labels**. The policy designer wrote these labels; they are not an
  independent human evaluation. `tap-explorer label-review` is the review view: the operator accepts or corrects each one and `--apply` writes a new corpus with those cases `human-reviewed`.

No APKs, screenshots, serials, absolute paths or provider responses are bundled. Owned node
rows are preserved, but foreign-package payloads—including phone status-bar/notification
text and selectors—are **not published**. Their structural signature features remain as
originally retained. Each case records the original local snapshot SHA256 and the number of
foreign nodes omitted. The original snapshot remains local; its hash is not the hash of this
privacy-filtered projection. Stored index selectors remain diagnostic, not executable approval.

Source graph/APK hashes pin the annotations. The builder also reconstructs each owned retained
feature list from the raw snapshot and rejects disagreement. Source `typed_values` comes from
successful recorded fills, not hand-entered literals or truth labels.

We deliberately commit a compact, self-contained JSON evidence file (~2.8 MB), rather than an
84k-line pretty-printed feature file plus a duplicated raw corpus. Labels need a compact review
view; this file is not a good manual annotation interface. Rebuild into a **new** file:

```bash
.venv/bin/python clients/explorer/benchmarks/build_corpus.py \
  --evidence-root .tap/explorer --out .tap/explorer/rebuilt-corpus.json
```

The builder refuses changed graph/APK hashes and unexpected finite fixture values. Loop's
binary name-error label currently uses observation range 7–16, based on inspected screenshots
5/7/11/15. That annotation is provisional and must not silently attach to a different run.
Our retained features still miss four pairs of empty-name observations with/without the icon.

Variant labels keep literal field bindings. List labels collapse record names/counts and the
creation Snackbar only for presentation scoring. These are **not robot-class labels**, backend
state equivalence, parameter-class truth or action authority. Two labeled menus may legitimately
share a generic `SingleChoiceDialog` helper. Robot reuse needs a separate human-reviewed scope.

## Fixed baselines and candidate

- `exact`: full retained-feature digest for screens and variants; current fine evidence identity.
- `skeleton`: package-scoped set of `(resource, class)` slots at both levels; deliberately
  unsafe as execution equivalence, useful as a counterexample.
- `skeleton-exact`: skeleton screens, exact variants.
- `similarity`: structure-set Jaccard overlap **>= 0.9**, visible text/description-set overlap
  **>= 0.5**, within a package. Editable nodes (legacy EditText fallback), captured placeholder
  text and exact literals from recorded fills are excluded from screen text. Variant evidence
  remains exact. These are fixed candidate thresholds supplied by the review, not tuned or
  validated general-purpose constants.

- `similarity-roles`: similarity screens; variants from role-normalized conditions
  (`similarity.variant_items`). Rows of a RecyclerView/ListView/GridView collapse to
  empty/nonempty (row content is data for a parameterized robot), the Snackbar library views
  and the anonymous wrappers that only hold them are dropped, editable values stay literal
  (empty when showing a hint), non-editable text equal to a recorded fill becomes `<typed>`, and
  CHECKABLE/CHECKED/SELECTED/ENABLED plus captured error/content-invalid are kept.

Similarity **re-clusters all observations** using complete-link agglomeration: every pair
across two proposed clusters must pass both thresholds. Prefer the strongest weakest-pair
normalized similarity; break ties by sorted case IDs. Chain and shuffled-order tests cover
A~B, B~C, A!~C. Single-link connected components are order-independent, but allow that chain.
Membership hashes are offline group IDs, not durable screen handles.

The token exclusions are hypotheses, not field-emptiness rules. Captured `showing_hint` should
inform future field interpretation; `text == hint` cannot prove emptiness. In particular,
excluding a typed literal everywhere may hide an identical static control label. A deliberate
counterexample (`Delete`/`Clear` as data and control text) makes this candidate fail the screen
merge gate. Do not promote it to general-purpose robot recognition based on this corpus.

## In-sample results

Merge/split counts below are **observation pairs**, not command failures or app accuracy.

| Policy | Source | Screen groups (truth / predicted) | Screen merges / splits | Variant merges / splits |
|---|---|---|---|---|
| exact | Wizard | 7 / 30 | 0 / 368 | 0 / 0 |
| exact | Loop | 8 / 24 | 0 / 249 | 4 / 31 |
| skeleton | Wizard | 7 / 7 | 0 / 0 | 368 / 0 |
| skeleton | Loop | 8 / 8 | 4 / 18 | 222 / 14 |
| skeleton-exact | Wizard | 7 / 7 | 0 / 0 | 0 / 0 |
| skeleton-exact | Loop | 8 / 8 | 4 / 18 | 4 / 31 |
| similarity | Wizard | 7 / 7 | 0 / 0 | 0 / 0 |
| similarity | Loop | 8 / 8 | 0 / 0 | 4 / 31 |
| similarity-roles | Wizard | 7 / 7 | 0 / 0 | 0 / 0 |
| similarity-roles | Loop | 8 / 8 | 0 / 0 | 4 / 0 |

The candidate separates the two dropdown menus and groups the list with/without the creation
Snackbar without special resource-ID exclusions. Saved nodes identify `snackbar_text` inside
Loop's app window: our earlier "toast" description was wrong, and window type would not fix
that split. `similarity-roles` removes all 31 Loop variant splits (21 truth / 20 predicted
variants). The 4 remaining merge pairs are the blank-name error icon: the UiAutomator dump has
no `error`/`content_invalid`, so they need the approved diagnostic driver capture. The role
rules were written while looking at this corpus: treat them as in-sample, like the thresholds.

## Metrics and promotion gate

- **False-merge pairs:** different truth labels grouped together.
- **False-split pairs:** identical truth labels separated.
- Pair precision/recall: correctly grouped pairs divided by predicted-same/truth-same pairs;
  undefined ratios are null, not artificial 100% scores.
- **Merged truth-cluster pairs:** distinct unordered pairs of truth families mixed in at least
  one prediction, counted once regardless of repeated captures.
- **Impure predicted clusters:** prediction groups containing more than one truth family.
- **Split truth clusters:** truth families spread across multiple prediction groups.

For Loop, skeleton merges one pair of distinct families in one impure cluster, and splits one
family; similarity has zero of each on the supplied labels. Any screen merge fails the
`screen_merge_gate`; splits cannot compensate. `--require-no-screen-merges` returns 1 if any
selected policy fails. Reports retain per-source results, provenance, corpus digest, omitted
variant counts and bounded examples. Wizard sampling is twice per state; Loop includes every
capture. The overall pair count weights these uneven samples, not independent apps equally.

The table above is **in-sample**, with provisional Loop labels; the held-out wizard set below
is the only out-of-sample evidence. A passing gate means only no merges against
these supplied labels. No landmark uniqueness/recognition, assertion discrimination, visual
false-alarm, history-equivalence or robot-edit/execute metric is implemented yet. Raw evidence
makes those future checks possible; it does not establish them. Live discovery remains unchanged.

## Held-out wizard runs

`corpus-held-out-wizard.json` (127 cases) comes from four fresh unseeded runs of the same
fixture APK on a different device (the API 34 emulator; the in-sample run was a Samsung API 29
phone), each started from the same emulator snapshot with animations off. Its labels come only
from `wizard_label`, the fixture's source-defined oracle, which existed before the runs; the
file was written, and its hash recorded, before any policy was scored on it. It shares no
snapshot with `corpus-v2.json`. Rebuild with:

```bash
.venv/bin/python clients/explorer/benchmarks/build_corpus.py \
  --held-out .tap/explorer/held-out-wizard-{1,2,3,4} --out .tap/explorer/held-out.json
```

Each run is a source, sampled like the in-sample wizard (first and last capture per state).
Runs 1–3 stopped early, and safely: a moving capture, a tap that raced a re-layout
(`StaleObjectException`, recorded as indeterminate) and a route "divergence" caused by a
status-bar notification icon. Run 4 resolved the reviewed frontier: 30 states, 236 attempts, no
indeterminate outcome.

| Policy | Screen merges / splits | Variant merges / splits |
|---|---|---|
| exact (per run) | 0 / 12–368 | run 3: 0 / 6; others 0 / 0 |
| skeleton | 0 / 0 | 1368 / 0 |
| similarity | 0 / 0 | per run as exact |
| similarity-roles | 0 / 0 | 0 / 0 |

`similarity-roles` groups every run's screens and variants without an error. Exact identity
split two of run 3's variants: the status-bar icon was part of the `tap-state-signature/1`
foreign-window structure. `/2` keeps only each foreign window's root and direct children, and
run 4 (captured with `/2`) has no split. Pooled across runs, exact identity also splits
`/1`-from-`/2` captures; read it per run.

What this does and does not show: the same app on a new device and new runs, labeled by an
oracle that predates them. It is **not** a held-out *app*. The role rules have not met an app
they were not written against; Loop's labels are still provisional until reviewed with
`label-review`, and a fresh Loop run would need human labels before it is scored.

## Robot answer keys

`keys/` holds hand-written answer keys (`tap-robots-key/1`) for `tap-explorer robots-score`. Each
lists the screens with `recognize` rules (resource, text or absence), the methods a robot should
have (verb, control, destination screen, and a precondition when the outcome depends on state),
and the checks a tester would write (`value`, `checked`, `shown`, `list`, `error`). Entries
marked `optional` count when drafted and are not required. Each key records its provenance:

- `wizard.json` (`fixture-source`) comes from `samples/explorer-machine`'s `MainActivity.kt`:
  its pages, buttons and the `name.isEmpty()` and `pro && !accepted` rules.
- `loop.json` (`analyst-provisional`) comes from Loop Habit Tracker's source at commit
  `516bf394`: `EditHabitActivity.validate` (a name, plus a target for a measurable habit),
  `HabitTypeDialog`, `FrequencyPickerDialog` and the list menu. Its scope is the habit list, the
  type chooser, both create forms with their pickers, and the filter menu. It needs the
  operator's review before it counts as evidence.

Both runs below are on the disposable API 34 emulator (emulator-5560), each started from the
`robots-ready` snapshot with animations off; every action was approved by the operator. The
Loop run is `.tap/explorer/loop-emulator-5`: 44 attempts, 26 states, none indeterminate. It used
a server and client that report `ScreenNode.error`.

| | Wizard: draft → answered | Loop: draft → answered |
|---|---|---|
| Robots / key screens | 7 / 7, no split or mix | 8 / 8, no split or mix |
| Exact `verify()` landmarks | 7 / 7 | 8 / 8 |
| Methods: recall (of the key / of what was tried), precision | 1.0 / 1.0, 1.0 | 0.94 / 1.0, 1.0 |
| Preconditions right of keyed | 3 / 4 (1 offered) → 4 / 4 | 2 / 4 (2 offered) → 4 / 4 |
| Checks: recall, precision | 0.82, 1.0 | 0.89, 1.0 |
| Questions (all answerable) | 12: 7 names, 4 outcomes, 1 workflow | 15: 8 names, 4 outcomes, 3 workflows |

What remains:

- Loop's two unscored methods are frequency fields the operator never filled.
- Loop's missing check is the habit list: its rows have no resource ID, so no list check is
  drafted.
- The wizard's missing check is the plan summary on two pages: only one name was ever typed,
  so the summary never varied there.
- Answering the questions fixes every precondition, but nothing else in the score. The names
  they ask for are not scored.

Before field errors were captured, Loop's check recall was 0.56. Before the draft offered the
untested combination, both Loop Save preconditions were wrong with and without a human.

These are the runs the changes were developed on, not held-out evidence: the next app should be
scored before anything is tuned on it.
