"""Hand-written examples and notes for the one-page tap-agent command reference.

gen_reference_page.py takes everything else (summaries, usage, options, MCP tools, grouping)
from the CLI parser, the MCP server and gen_reference.GROUPS. clients/agent/tests/
test_reference_page.py fails when a verb has no example or an example no longer parses, so
these stay in step with the CLI. Outputs follow the real formats, recorded on an API 34
emulator; the app (`com.shop`) is made up so the examples read as one session.
"""

from __future__ import annotations

# verb -> [(command line, output)]. An empty output prints no output block.
EXAMPLES: dict[str, list[tuple[str, str]]] = {
    # Sessions and devices
    "devices": [
        ("tap-agent devices",
         "85e49002  FREE  API 29  samsung SM-J810G  physical\n"
         "emulator-5554  LEASED  API 34  Google sdk_gphone64_x86_64  emulator  held by 'agent'"),
        ("tap-agent devices unquarantine 85e49002", "85e49002 is not quarantined; nothing to do"),
    ],
    "attach": [
        ("tap-agent attach emulator-5554", "attached emulator-5554 to session 'agent', idle timeout 15m"),
        ("tap-agent attach 85e49002 --wait-for-device 2m --idle 30m",
         "attached 85e49002 to session 'agent', idle timeout 30m"),
    ],
    "sessions": [("tap-agent sessions", "agent  idle 2s of 15m  emulator-5554\nci  idle 4m of 15m  85e49002")],
    "detach": [("tap-agent detach -d 85e49002", "detached 85e49002 from session 'agent'")],
    "release": [("tap-agent release", "released session 'agent' (detached emulator-5554)")],
    "server-info": [
        ("tap-agent server-info",
         "daemon version     0.2.0\n"
         "protocol version   5.3\n"
         "adb executable     adb\n"
         "state dir          /home/me/.tap\n"
         "driver available   yes\n"
         "defaults           action 10, wait 10, lifecycle 30, idle_stable 0.2, acquire 300\n"
         "api compatibility  2"),
    ],
    "device-info": [
        ("tap-agent device-info",
         "api level            34\n"
         "model                sdk_gphone64_x86_64\n"
         "display width        1080\n"
         "display height       2400\n"
         "display rotation     NATURAL\n"
         "current package      com.shop\n"
         "screen on            yes\n"
         "keyguard locked      no\n"
         "keyboard shown       no\n"
         "animations enabled   yes\n"
         "dark mode            no\n"
         "font scale           1\n"
         "density dpi          420\n"
         "foreground activity  com.shop/com.shop.CheckoutActivity"),
    ],
    "capabilities": [
        ("tap-agent capabilities",
         "TAP  SUPPORTED  Execution-time checks and Android acceptance still apply.\n"
         "DISMISS_KEYGUARD  CONDITIONAL  Only a keyguard without a PIN, pattern or password can be dismissed. …\n"
         "KEYBOARD_IME_ACTION  CONDITIONAL  Requires Android API 30; device API 34. …\n"
         "RECORD_AUDIO_PLAYBACK  CONDITIONAL  Requires Android API 33; device API 34. …\n"
         "…"),
    ],
    # Reading the screen
    "snapshot": [
        ("tap-agent snapshot",
         "# com.shop · window 1\n"
         '@e1  [TextView]  "Checkout"\n'
         '@e2  [EditText]  empty  hint="Email"  id=email  accepts=tap,long,edit\n'
         '@e3  [CheckBox]  "Save card"  accepts=check\n'
         '@e4  [Button]  "PAY $12.40"  id=pay  accepts=tap\n'
         "# com.android.systemui · window 2 · status bar (6 nodes, collapsed)"),
        ("tap-agent snapshot -i --package com.shop",
         "# com.shop · window 1\n"
         '@e2  [EditText]  empty  hint="Email"  id=email  accepts=tap,long,edit\n'
         '@e3  [CheckBox]  "Save card"  accepts=check\n'
         '@e4  [Button]  "PAY $12.40"  id=pay  accepts=tap'),
    ],
    "inspect": [
        ("tap-agent inspect @e2",
         '[EditText]  empty  hint="Email"  id=email\n'
         "package   com.shop\n"
         "bounds    [63,126][1017,244]\n"
         "state     enabled clickable long-clickable focusable\n"
         "actions   show-on-screen, select, clear-selection, focus"),
    ],
    "exists": [("tap-agent exists text~=Pay", "yes"), ("tap-agent exists text=Receipt", "no")],
    "count": [("tap-agent count class=EditText", "2")],
    "elements": [
        ("tap-agent elements class=EditText",
         '[EditText]  "me@example.com"  hint="Email"  id=email\n[EditText]  empty  hint="Card number"  id=card'),
        ("tap-agent elements class=Button --texts", '"PAY $12.40"\n"CANCEL"'),
    ],
    "settle": [
        ("tap-agent settle",
         "# com.shop · window 1\n"
         '+ @e6  [TextView]  "Paid. Receipt #4471"\n'
         '+ @e7  [Button]  "DONE"  id=done  accepts=tap\n'
         "# removed\n"
         '- @e4  [Button]  "PAY $12.40"  id=pay  accepts=tap'),
        ("tap-agent settle --signal pixels --stable-for 1", "(no change)"),
    ],
    "wait": [
        ("tap-agent wait text~=Receipt", "text~=Receipt is visible"),
        ("tap-agent wait text=Loading --gone", "text=Loading is gone"),
        ("tap-agent wait id=pay --state enabled", "id=pay is enabled"),
        ("tap-agent wait id=pay --until enabled=true,text~=Pay", "id=pay satisfies enabled=true,text~=Pay"),
    ],
    "activity": [("tap-agent activity", "com.shop/com.shop.CheckoutActivity")],
    "screenshot": [("tap-agent screenshot -o checkout.png", "checkout.png")],
    "capture": [
        ("tap-agent capture checkout-bug",
         "capture checkout-bug saved to .tap/agent/capture-emulator-5554-checkout-bug-20261121-215915-931\n"
         ".tap/agent/capture-…/emulator-5554.screenshot.png\n"
         ".tap/agent/capture-…/emulator-5554.hierarchy.xml\n"
         ".tap/agent/capture-…/emulator-5554.snapshot.txt\n"
         ".tap/agent/capture-…/emulator-5554.device-info.json\n"
         ".tap/agent/capture-…/emulator-5554.logcat.txt\n"
         ".tap/agent/capture-…/emulator-5554.history.txt\n"
         "…"),
        ("tap-agent capture list", "2026-11-21T19:59:18.401Z  checkout-bug  emulator-5554  2.6MiB"),
        ("tap-agent capture clean checkout-bug",
         "cleaned capture checkout-bug (.tap/agent/capture-emulator-5554-checkout-bug-20261121-215915-931)"),
    ],
    "deaths": [
        ("tap-agent deaths", "#1 com.shop crashed (pid 23554): java.lang.IllegalStateException: Out of stock"),
        ("tap-agent deaths --trace 1",
         "com.shop crashed (pid 23554): java.lang.IllegalStateException: Out of stock\n\n"
         "java.lang.IllegalStateException: Out of stock\n"
         "\tat com.shop.Cart.pay(Cart.kt:88)\n"
         "\tat com.shop.CheckoutActivity.onPay(CheckoutActivity.kt:41)"),
    ],
    "media-record": [
        ("tap-agent media-record start --max 20s", "recording started"),
        ("tap-agent media-record stop -o checkout.mp4", "saved recording to checkout.mp4"),
    ],
    # Acting on elements
    "tap": [
        ("tap-agent tap @e4", "tapped @e4"),
        ("tap-agent tap id=pay --settle",
         "tapped id=pay\n"
         "# com.shop · window 1\n"
         '+ @e6  [TextView]  "Paid. Receipt #4471"\n'
         "# removed\n"
         '- @e4  [Button]  "PAY $12.40"  id=pay  accepts=tap'),
        ("tap-agent tap id=save --expect toast:saved",
         'tapped id=save\nevents:\n✓ toast "Card saved" (com.shop)'),
        ("tap-agent tap text=Item --long", "long-tapped text=Item"),
    ],
    "fill": [
        ("tap-agent fill id=email me@example.com", "filled id=email"),
        ("tap-agent fill id=card 4111111111111111 --sensitive", "filled id=card"),
    ],
    "type": [
        ("tap-agent type ' extra' id=email", "typed 6 characters"),
        ("tap-agent type 1234", "typed 4 characters"),
    ],
    "clear": [("tap-agent clear id=email", "cleared id=email")],
    "submit": [("tap-agent submit id=search", "submitted id=search")],
    "action": [
        ("tap-agent action @e9", "@e9 offers: expand, focus, 'Archive', 'Mark as read'"),
        ("tap-agent action @e9 expand", "performed expand on @e9"),
        ("tap-agent action @e9 Archive --custom", "performed 'Archive' on @e9"),
    ],
    "progress": [("tap-agent progress id=volume 40", "set progress 40 on id=volume")],
    "keyboard": [("tap-agent keyboard", "keyboard shown"), ("tap-agent keyboard hide", "keyboard hidden")],
    # Gestures
    "scroll": [("tap-agent scroll id=feed down", "scrolled down (finger up) id=feed")],
    "scroll-until": [
        ("tap-agent scroll-until id=feed text~=Receipt", "found text~=Receipt inside id=feed"),
    ],
    "swipe": [("tap-agent swipe id=card --finger left", "swiped finger left id=card")],
    "fling": [("tap-agent fling id=feed down", "flung down (finger up) id=feed")],
    "drag": [("tap-agent drag text=Milk id=basket", "dragged text=Milk onto id=basket")],
    "pinch": [("tap-agent pinch id=map open --percent 60", "pinched open 60% id=map")],
    # Keys, system panels and the display
    "key": [
        ("tap-agent key back", "pressed back"),
        ("tap-agent key volume-up --count 2", "pressed volume-up x2"),
        ("tap-agent key volume-down --duration 2s", "long-pressed volume-down for 2s"),
        ("tap-agent key power --long", "long-pressed power"),
        ("tap-agent key --list", "soft-left  1\nsoft-right  2\nhome  3\nback  4\n…"),
    ],
    "panel": [("tap-agent panel notifications", "opened notifications")],
    "rotate": [("tap-agent rotate landscape", "rotated landscape: display landscape, left (2400x1080)")],
    "screen": [("tap-agent screen", "screen on, keyguard not showing"),
               ("tap-agent screen unlock", "screen on, keyguard not showing")],
    # Apps and permissions
    "app": [
        ("tap-agent app cold-launch com.shop", "cold-launched com.shop (pid 4242)"),
        ("tap-agent app launch com.shop .ui.ItemActivity --extra item:long=42", "launched com.shop"),
        ("tap-agent app grant com.shop android.permission.CAMERA", "granted android.permission.CAMERA to com.shop"),
        ("tap-agent app running com.shop", "com.shop is running"),
    ],
    "permission": [
        ("tap-agent permission",
         "permission dialog (com.android.permissioncontroller) offers: allow-foreground-only, allow-one-time, deny"),
        ("tap-agent permission allow-one-time", "pressed allow-one-time"),
    ],
    # Debuggable app data
    "app-data": [
        ("tap-agent app-data list com.shop databases", 'file 20480 "shop.db"\nfile 8192 "shop.db-wal"'),
        ("tap-agent app-data export-db com.shop shop.db ./shop-db",
         "saved database bundle to ./shop-db (keep database and sidecars together)"),
    ],
    # Device conditions and location
    "condition": [
        ("tap-agent condition",
         "animations on\ndark-mode off\nfont-scale 1\ndensity 420 dpi\nairplane-mode off\nwifi on\n"
         "mobile-data on\nlocale en-US\nstay-awake on\nime-with-hard-keyboard on\nhigh-contrast-text off\n"
         "color-inversion off\nbold-text off"),
        ("tap-agent condition locale --json", '{\n  "locale": [\n    "en-US"\n  ]\n}'),
        ("tap-agent condition dark-mode on", "dark-mode on (restored on release)"),
    ],
    "location": [("tap-agent location 52.37 4.89", "location mocked at 52.37, 4.89 (ends on release)")],
    # Notifications, toasts and the clipboard
    "notification": [
        ("tap-agent notification",
         "com.shop: 'Order shipped' - 'Arrives Tuesday' [Track, Dismiss]\n"
         "com.android.systemui: 'Virtual SD card' - 'For storing photos, videos, music and more' [Explore, Eject]"),
        ("tap-agent notification open --title 'Order shipped' --button Track", "pressed 'Track' on the notification"),
    ],
    "toast": [
        ("tap-agent toast", "#1 1795275040120 toast 'Card saved' from 'com.shop'\ntoast cursor: --after-seq 1 --stream 6793fef1-…"),
        ("tap-agent toast saved --wait", "toast 'Card saved' from com.shop"),
    ],
    "clipboard": [("tap-agent clipboard SAVE20", "clipboard set (6 characters)"), ("tap-agent clipboard", "SAVE20")],
    # Files and the gallery
    "push": [("tap-agent push ./fixtures/cart.json /sdcard/Download/cart.json",
              "pushed 2048 bytes to /sdcard/Download/cart.json (removed on release)")],
    "pull": [("tap-agent pull /sdcard/Download/receipt.pdf", ".tap/agent/out/receipt.pdf")],
    "media": [("tap-agent media ./fixtures/receipt.jpg",
               "added /sdcard/Pictures/tap/receipt.jpg to the gallery (removed on release)")],
    # Turning a session into a test
    "history": [
        ("tap-agent history --last 1m",
         '2026-11-21T19:56:42.242Z  emulator-5554  step 93: fill res("email") me@example.com → succeeded\n'
         '2026-11-21T19:56:51.096Z  emulator-5554  step 97: tap res("pay") settle → succeeded\n'
         "2026-11-21T19:56:51.610Z  emulator-5554  CRASH com.shop (pid 23554): java.lang.IllegalStateException: Out of stock\n"
         '2026-11-21T19:58:56.721Z  emulator-5554  step 98: wait res("receipt") → failed (WAIT_TIMEOUT)'),
        ("tap-agent history mark checkout", "mark emulator-5554 'checkout' at d01dda20-5b45-4b6e-847b-b51d15b486d5:3959"),
        ("tap-agent history keep start checkout", "recording 'checkout' (devices attached later join it)"),
        ("tap-agent history keep stop checkout", "stopped recording 'checkout'"),
        ("tap-agent history --kept checkout",
         "recording checkout (stopped)\n"
         '2026-11-21T19:56:42.242Z  emulator-5554  step 93: fill res("email") me@example.com → succeeded\n'
         "…"),
    ],
    # Device events
    "watch": [
        ("tap-agent watch toast window --for 10s",
         'toast "Card saved" (com.shop)\nwindow state com.shop.ReceiptActivity "Receipt" (com.shop)'),
        ("tap-agent watch window --until-first --package com.shop --timeout 5s",
         'window state com.shop.ReceiptActivity "Receipt" (com.shop)'),
    ],
    # Agent setup
    "mcp": [("tap-agent mcp", "")],
    "skill": [("tap-agent skill", "---\nname: tap-agent\n…")],
}

# verb -> notes shown under the examples. Things the options table cannot say.
NOTES: dict[str, list[str]] = {
    "snapshot": [
        "`accepts=` says what the node takes: `tap`, `long`, `check`, `edit`, `scroll`, or `actions` "
        "for accessibility actions only (see `action`).",
        "The keyboard and the status and navigation bars collapse to one header line each; "
        "`--keyboard show` and `--system-bars show` list their nodes, `omit` drops them, and `--window N` "
        "shows one window in full.",
    ],
    "tap": [
        "With `--settle` the step waits for the screen to stop changing and prints the difference: "
        "`+` added, `-` removed.",
        "A refusal says what to do next, for example `several nodes match; use a ref from snapshot or a "
        "narrower selector`. Nothing is sent to the device.",
    ],
    "type": ["The text comes first: `type TEXT [TARGET]`. `fill TARGET TEXT` replaces the field's text instead."],
    "keyboard": ["`hide` sends one Back key only when a keyboard shows, then waits up to 2 s for it to go."],
    "action": ["Click is not an action here: use `tap`. An action the node does not offer is refused before "
               "anything happens."],
    "key": ["As for `tap`: `--long` holds for the device's long-press time, `--duration` for a time you give "
            "(and implies `--long`); `--count N` repeats with `--interval` between presses."],
    "capture": ["`screenshot` saves only the image and `snapshot` saves nothing; `capture` is the bundle to "
                "attach to a bug. `-o DIR` must be a new directory: nothing is overwritten."],
    "history": [
        "A window that ends now (`--last`, `--last-action`) shows the newest page and ends with "
        "`older: --after POSITION`; `--from`, `--since-marker` and `--step` read forward.",
        "Missing evidence is never silent: a `GAP:` line where capture stopped, or a `WARNING:` line.",
    ],
    "deaths": ["Any step that makes a watched app crash exits with code 4 and prints the crash under its output."],
    "mcp": ["Serves MCP on stdin and stdout until the client disconnects. CLI and MCP share sessions and history."],
}
