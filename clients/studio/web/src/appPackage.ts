import { useState } from "react";

const PACKAGE_KEY = "tap-studio.package";

function remembered(): string {
  try {
    return window.localStorage.getItem(PACKAGE_KEY) ?? "";
  } catch {
    return "";
  }
}

/**
 * The package the App menu acts on (launch, stop, clear data, grant, and the cold launch a replay
 * can start with). It is the page's choice, not the attach's: a device names no app. Remembered in
 * this browser as a convenience.
 */
export function useAppPackage(): [string, (pkg: string) => void] {
  const [pkg, setPkg] = useState(remembered);
  const set = (next: string) => {
    setPkg(next);
    try {
      window.localStorage.setItem(PACKAGE_KEY, next.trim());
    } catch {
      // Storage off (private window): only a convenience.
    }
  };
  return [pkg, set];
}
