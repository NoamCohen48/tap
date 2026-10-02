// The small building blocks of the composer and the app panel: a titled group, a labelled row of
// buttons, and the four direction buttons.

import type { ReactNode } from "react";
import { Direction } from "./gen/command_pb";
import { Arrow } from "./icons";

export const DIRECTIONS: { to: "up" | "down" | "left" | "right"; direction: Direction; name: string }[] = [
  { to: "up", direction: Direction.DIR_UP, name: "up" },
  { to: "down", direction: Direction.DIR_DOWN, name: "down" },
  { to: "left", direction: Direction.DIR_LEFT, name: "left" },
  { to: "right", direction: Direction.DIR_RIGHT, name: "right" },
];

export const directionName = (direction: Direction) => DIRECTIONS.find((d) => d.direction === direction)?.name ?? "down";

export function Group({ title, note, children }: { title: string; note?: ReactNode; children: ReactNode }) {
  return (
    <section className="group" aria-label={title}>
      <h3>
        {title}
        {note && <span className="note">{note}</span>}
      </h3>
      {children}
    </section>
  );
}

/** A row: what it does on the left, its controls beside it, and why they cannot run under them. */
export function Row({ label, children, why }: { label: string; children: ReactNode; why?: string }) {
  return (
    <div className="crow">
      <span className="rl">{label}</span>
      <div className="actions">{children}</div>
      {why && <div className="why">{why}</div>}
    </div>
  );
}

/** Four arrow buttons, named "<verb> up" and so on. */
export function Directions({ verb, disabled, onDirection }: { verb: string; disabled: boolean; onDirection: (direction: Direction) => void }) {
  return (
    <>
      {DIRECTIONS.map((d) => (
        <button
          key={d.name}
          type="button"
          className="btn dir"
          disabled={disabled}
          aria-label={`${verb} ${d.name}`}
          title={`${verb} ${d.name}`}
          onClick={() => onDirection(d.direction)}
        >
          <Arrow to={d.to} />
        </button>
      ))}
    </>
  );
}
