# Operator decisions and next PR scope

The operator answered the four approval questions after the follow-up review.

## Approved boundaries

- **Environment:** a separate dedicated disposable emulator/AVD, with restoration of its own
  snapshots between trials. Existing/shared emulators and physical phone 85e49002 stay
  restricted. This is not blanket approval of arbitrary actions; app risk policies need their
  own review. Restoration must not resume/replay a transmitted mutation or reuse an old session.
- **AI:** deferred. No provider calls or model upgrades.
- **Driver:** additive, diagnostic-only capture of `error`, `content_invalid` and
  `showing_hint`, including contract/client mappings and regressions. Selector lookup and
  mutation behavior must not change. Window type/layer/title, pane title and state description
  remain deferred.
- **Labels:** prepare a compact review view; the operator will confirm/correct labels there.
  Analyst annotations remain provisional until the operator submits review.

## Immediate PR

One scope: similarity candidate, raw snapshot corpus evidence, cluster-level metrics and
complete-linkage chaining/order regressions. No driver change, emulator reset, device input,
AI use, row-selector changes or robot generation in this PR. Isolate it from the existing
worktree's unrelated/unpublished changes.

Single-linkage connected components are order-independent but permit chaining. Complete-link
merges must check every cross-cluster pair and use deterministic tie-breaking. Re-cluster the
full offline observation set; do not rely on arrival-order assignment. Preserve exact execution
fingerprints and approval checks.

A crude **unverified** Loop robot draft follows this step, rather than waiting for every
recognition/coverage improvement. It is a separate deliverable. Human review tooling and safe
row-selector work can proceed as independent work streams; measure coverage gains through
new screens actually reached, not unit-test counts alone.

The original report and assessment are retained because the operator explicitly requested
that they be saved. Accepted decisions also belong in the main design record.
