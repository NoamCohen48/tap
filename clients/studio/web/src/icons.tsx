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

export const AppIcon = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
    <rect x="4" y="4" width="16" height="16" rx="3" />
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
