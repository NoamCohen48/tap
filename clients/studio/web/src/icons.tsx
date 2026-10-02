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

export const Back = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M15 18l-6-6 6-6" />
  </svg>
);

export const Home = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <circle cx="12" cy="12" r="7" />
  </svg>
);

export const Recents = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <rect x="5" y="5" width="14" height="14" rx="2" />
  </svg>
);

export const Bell = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z" />
    <path d="M10 20.5a2 2 0 0 0 4 0" />
  </svg>
);

export const Toggles = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <rect x="3" y="4.5" width="18" height="6" rx="3" />
    <circle cx="15" cy="7.5" r="1.2" />
    <rect x="3" y="13.5" width="18" height="6" rx="3" />
    <circle cx="9" cy="16.5" r="1.2" />
  </svg>
);

export const AppIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <rect x="4" y="4" width="6.5" height="6.5" rx="1.5" />
    <rect x="13.5" y="4" width="6.5" height="6.5" rx="1.5" />
    <rect x="4" y="13.5" width="6.5" height="6.5" rx="1.5" />
    <rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.5" />
  </svg>
);

export const Download = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M12 4v11m0 0l-4-4m4 4l4-4M5 20h14" />
  </svg>
);

export const Close = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M6 6l12 12M18 6L6 18" />
  </svg>
);

export const Eject = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M12 5l7 8H5zM5 19h14" />
  </svg>
);

export const Play = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" fill="currentColor">
    <path d="M8 5v14l11-7z" />
  </svg>
);

export const Stop = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" fill="currentColor">
    <rect x="6" y="6" width="12" height="12" rx="1.5" />
  </svg>
);

export const Upload = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M12 16V4M7 9l5-5 5 5M5 20h14" />
  </svg>
);

export const Up = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M6 15l6-6 6 6" />
  </svg>
);

export const Down = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M6 9l6 6 6-6" />
  </svg>
);

export const Trash = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />
  </svg>
);

const ARROW: Record<"up" | "down" | "left" | "right", number> = { up: 0, right: 90, down: 180, left: 270 };

/** A direction arrow, for swipe and scroll: an arrow with a shaft, unlike the chevrons. */
export const Arrow = ({ to }: { to: keyof typeof ARROW }) => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke} style={{ transform: `rotate(${ARROW[to]}deg)` }}>
    <path d="M12 19V5M6 11l6-6 6 6" />
  </svg>
);
