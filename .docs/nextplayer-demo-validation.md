# NextPlayer Demo Validation

Date: 2026-09-17

NextPlayer at `../NextPlayer` is the representative complex application for
Tap's general-purpose Phase 0 validation. It is a real multi-module Compose application, not
a Tap fixture and not a source of product-specific assumptions in the driver or protocol.

## Application boundary

- Package: `com.example.nextplayer`
- Activity: `.MainActivity`
- UI: Jetpack Compose with Material 3, Navigation 3, Room, MediaStore, and Media3
- Minimum Android API: 31
- Validated product device: API 34 emulator
- API 29 result: intentionally unsupported by the application; Android rejected installation
  with `INSTALL_FAILED_OLDER_SDK` and required API 31.

Tap's driver and process-identity behavior remains validated independently on API 29 and API
34 with the framework fixture. A representative app is only expected to run on Android
versions that app supports.

## General product probe

The host executable now has a product-agnostic probe mode:

```text
host --product-probe SERIAL DRIVER_APK DRIVER_TEST_APK AUT_APK PACKAGE ACTIVITY \
  'TAP_TEXT|READY_TEXT|SCREEN_NAME'...
```

Use `-` as `TAP_TEXT` to benchmark the initial screen. Each screen reports:

- first cold direct lookup;
- 100 warm direct lookups with p50 and p95;
- 10 hierarchy dumps/transfers with p50 and p95;
- 100 host XML parse-and-query operations with p50 and p95; and
- AUT-scoped accessibility node, text, content-description, resource-ID, clickable, and
  unlabeled-clickable counts.

The mode uses the same authenticated driver, session generations, device lease, durable
journal, exact forwarding, and bounded cleanup as normal Tap validation. Navigation is passed
as data; `ProductProbe.kt` contains no NextPlayer package names or screen names.

## Measured screens

The API 34 emulator had two deterministic MP3 fixtures indexed by NextPlayer. Measurements
are host-observed wall-clock durations.

| Screen | Nodes | Cold direct | Direct p50 / p95 | Dump p50 / p95 | Host parse/query p50 / p95 |
|---|---:|---:|---:|---:|---:|
| Library | 74 | 644.4 ms | 96.3 / 100.1 ms | 87.5 / 238.5 ms | 2.4 / 4.0 ms |
| Settings | 66 | 57.6 ms | 96.0 / 103.9 ms | 120.4 / 190.5 ms | 1.0 / 2.0 ms |
| Song gestures | 77 | 52.8 ms | 96.0 / 100.1 ms | 115.9 / 191.3 ms | 0.8 / 1.5 ms |

The cold values include accessibility-tree settling and vary by navigation/animation state.
Warm direct lookup is consistently near 96 ms. Hierarchy p95 is substantially less stable
and can exceed direct p95 by 90-140 ms. Host XML work is inexpensive for these screens, so
device hierarchy generation and transfer dominate the dump path.

## Dynamic accessibility inventory

| Screen | Text nodes | Descriptions | Resource IDs | Clickable | Unlabeled clickable |
|---|---:|---:|---:|---:|---:|
| Library | 20 | 7 | 1 | 13 | 0 |
| Settings | 21 | 4 | 1 | 10 | 1 |
| Song gestures | 31 | 5 | 1 | 12 | 0 |

Counts are restricted to nodes whose package is `com.example.nextplayer`. The one resource ID
is the Android content container, not a stable screen control. NextPlayer has no production
Compose test tags projected as resource IDs, so successful navigation by visible text proves
Tap is not dependent on test-only identifiers.

The Settings hierarchy contains one independently clickable unlabeled toggle node. Source
inspection points to the ReplayGain row's separately interactive `Switch`; folder rows use the
stronger pattern of putting toggle semantics and the label on one parent row.

## Static accessibility findings

- Important icon actions generally have content descriptions, and the custom bottom bar
  exposes tab role and selected state.
- Search uses a custom `BasicTextField`; its visible placeholder is not a strong explicit
  semantic label.
- Visual section titles do not expose heading semantics.
- Song swipe, mini-player swipe, player-artwork swipe, and queue drag/reorder interactions do
  not provide equivalent custom accessibility actions.
- The queue reorder handle is described only as `Reorder` and does not announce position or
  expose move-earlier/move-later actions.
- Player artwork is labeled as artwork while click changes playback, so its action is not
  communicated by its accessible name.
- Some extended buttons duplicate visible text in `contentDescription`, which may produce
  redundant announcements depending on semantics merging.
- There are no WebViews. System surfaces are limited to permissions, MediaStore deletion,
  IME, and notifications.

These are demo-app findings, not reasons to add NextPlayer-specific selector behavior to Tap.
They define representative cases for future role/state, custom-action, and relative-selector
support.

## Source evidence

- App/build boundary: `../NextPlayer/app/build.gradle.kts`
- Navigation shell: `../NextPlayer/app/src/main/java/com/example/nextplayer/MainScreen.kt`
- Library: `../NextPlayer/feature/playlists/src/main/java/com/example/nextplayer/playlists/PlaylistsScreen.kt`
- Settings and gestures: `../NextPlayer/feature/settings/src/main/java/com/example/nextplayer/settings/screen/SettingsScreen.kt`
- Song-row gestures: `../NextPlayer/core/common/src/main/java/com/example/nextplayer/common/TrackItem.kt`
- Queue semantics: `../NextPlayer/feature/player/src/main/java/com/example/nextplayer/player/QueueScreen.kt`
- Player semantics: `../NextPlayer/feature/player/src/main/java/com/example/nextplayer/player/PlayerScreen.kt`
- Search field: `../NextPlayer/feature/search/src/main/java/com/example/nextplayer/search/SearchScreen.kt`
