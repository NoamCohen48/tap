// Inline SVG icons (no icon font: the page works offline). Decorative: the buttons carry text or
// an aria-label.

const stroke = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" } as const;

export const Logo = () => (
  <svg viewBox="0 0 64 64" aria-hidden="true">
    <rect x="15" y="5" width="34" height="54" rx="8" fill="none" stroke="currentColor" strokeWidth="4" />
    <line x1="27" y1="52" x2="37" y2="52" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    <circle cx="32" cy="29" r="11.5" fill="none" stroke="#FF6A3D" strokeWidth="3" />
    <circle cx="32" cy="29" r="5" fill="#FF6A3D" />
  </svg>
);

export const Play = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M7 5v14l11-7z" fill="currentColor" />
  </svg>
);

export const Pause = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M8 5v14M16 5v14" />
  </svg>
);

export const Record = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <circle cx="12" cy="12" r="6" fill="currentColor" />
  </svg>
);

export const Stop = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <rect x="7" y="7" width="10" height="10" rx="1.5" fill="currentColor" />
  </svg>
);

export const Scissors = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <circle cx="6" cy="6" r="3" />
    <circle cx="6" cy="18" r="3" />
    <path d="M20 4L8.1 15.9M14.5 14.5L20 20M8.1 8.1L12 12" />
  </svg>
);

export const Live = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M5 5l7 7-7 7M13 5l7 7-7 7" />
  </svg>
);

export const Search = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <circle cx="11" cy="11" r="7" />
    <path d="M20 20l-3.5-3.5" />
  </svg>
);

export const Download = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M12 4v11M7 10l5 5 5-5M5 20h14" />
  </svg>
);

export const Trash = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />
  </svg>
);

export const Close = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M6 6l12 12M18 6L6 18" />
  </svg>
);

export const Alert = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7v6M12 16.5v.5" />
  </svg>
);

export const Check = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M5 12.5l4.5 4.5L19 7.5" />
  </svg>
);

export const Compare = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <rect x="3" y="5" width="7.5" height="14" rx="1.5" />
    <rect x="13.5" y="5" width="7.5" height="14" rx="1.5" />
  </svg>
);

export const Plus = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M12 5v14M5 12h14" />
  </svg>
);

export const Minus = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M5 12h14" />
  </svg>
);

export const Info = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 11v6M12 7.5v.5" />
  </svg>
);
