// Android key codes the page names: the device bar's keys menu offers them, and a recorded
// `pressKey` shows the name beside its code.

export const KEY_HOME = 3;
export const KEY_BACK = 4;
export const KEY_APP_SWITCH = 187;

/** The keys the menu offers, by what they are for: entering text, then the hardware keys. */
export const KEYS: { code: number; name: string; title: string }[] = [
  { code: 66, name: "Enter", title: "KEYCODE_ENTER: submits a single-line field or adds a line" },
  { code: 67, name: "Delete", title: "KEYCODE_DEL: deletes the character before the cursor (backspace)" },
  { code: 61, name: "Tab", title: "KEYCODE_TAB: moves focus to the next field" },
  { code: 62, name: "Space", title: "KEYCODE_SPACE" },
  { code: 111, name: "Escape", title: "KEYCODE_ESCAPE" },
  { code: 84, name: "Search", title: "KEYCODE_SEARCH" },
  { code: 24, name: "Volume up", title: "KEYCODE_VOLUME_UP" },
  { code: 25, name: "Volume down", title: "KEYCODE_VOLUME_DOWN" },
];

const NAMES: Record<number, string> = {
  ...Object.fromEntries(KEYS.map((k) => [k.code, k.name])),
  [KEY_APP_SWITCH]: "Recent apps",
};

/** The name of a key code the page knows, if any. */
export const keyName = (code: number): string | undefined => NAMES[code];
