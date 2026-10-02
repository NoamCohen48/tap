# Optional test markers — deferred proposal

Status: **Deferred by the owner during watcher UI exploration. Not implemented.**

The first watcher client displays connections, attached devices and their action logs.
It does not infer tests from connection names, commands, SDK usage, or process names.
A connection can belong to JUnit, pytest, tap-agent, Studio or an ordinary script.
The current demo is `.docs/test-watcher-demo.html`.

## Future integration

If test grouping is wanted later, test plugins can send optional explicit markers into
an otherwise generic connection event log. Normal command APIs remain unchanged.

Proposed marker fields:

- `id`: JUnit unique ID or pytest node ID.
- `display_name`: supplied by the test framework.
- `phase`: `STARTED` or `FINISHED`.
- `serials`: the devices held by this test.
- On finish: `outcome` (`PASSED`, `FAILED`, `SKIPPED`, `ABORTED`) and an optional
  one-line failure message.

A proposed `ClientConnectionService.Mark` RPC appends the marker. The daemon does not
execute tests or discover their names. Delivery is best effort; marker failure must
never fail a test or prevent cleanup. Public SDK helpers are optional reporting APIs,
not prerequisites for attaching or issuing commands.

### JUnit

`TapExtension` would send `STARTED` after successful device attachment in `beforeEach`.
After the test, it would capture configured failure artifacts, report `FINISHED` using
the framework result, and then detach. Authors keep their existing test code.

Implementation must account for setup failures, aborts, teardown errors, and JUnit
callback ordering. Do not report a successful final outcome before relevant teardown
failures are known; the precise lifecycle needs tests before implementing it.

### pytest

The plugin would use the pytest node ID, fixture device bookkeeping and
`pytest_runtest_makereport`. It would report start after attachment, then the final
outcome with appropriate fixture cleanup coordination. Authors keep existing fixtures
and test bodies.

Setup/call/teardown reports must be combined correctly. Teardown can fail after a
successful call; skip and setup failures can occur without any device attachment.
The exact finish/cleanup ordering remains an implementation decision, not a proven
property of the current plugin.

### Viewer behavior

Group only explicitly marked intervals, by connection and serial. Actions outside a
known interval remain ordinary connection actions. Missing markers, retained-backlog
eviction, disconnects or overlapping intervals must not manufacture test identities or
outcomes. Concurrent tests on distinct serials must remain distinct.

## Required verification before adoption

- Correct identifiers, display names and device association for both frameworks.
- Setup failure, skipped/aborted tests, teardown failure and disconnect handling.
- Concurrent tests and incomplete/evicted marker intervals.
- Failed reporting never changes the test result or prevents detach.
- Unmarked tap-agent, Studio and SDK connections work unchanged.

No marker proto, plugin changes or test-grouped UI are part of the current demo work.
