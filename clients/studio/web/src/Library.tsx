import { timestampDate } from "@bufbuild/protobuf/wkt";
import { useCallback, useEffect, useId, useState } from "react";
import type { StudioClient } from "./api";
import { ChoiceDialog } from "./Dialogs";
import { errorMessage } from "./frames";
import type { LibraryEntry, Session } from "./gen/studio_pb";

const steps = (count: number) => `${count} step${count === 1 ? "" : "s"}`;

/**
 * The private library of named recordings on this machine. It needs no device and no Tap server.
 * It keeps definitions only: never a secret's value, and no results.
 */
export function Library({
  client,
  session,
  busy,
  open = false,
  onSession,
}: {
  client: StudioClient;
  session: Session;
  /** Studio is busy, or another tab controls it. */
  busy: boolean;
  /** Shown open, when it is the main thing on the page. */
  open?: boolean;
  onSession: (session: Session) => void;
}) {
  const id = useId();
  const link = session.library;
  const [entries, setEntries] = useState<LibraryEntry[] | null>(null);
  const [unreadable, setUnreadable] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [renaming, setRenaming] = useState<{ entry: LibraryEntry; name: string } | null>(null);
  const [confirming, setConfirming] = useState<{ kind: "open" | "delete"; entry: LibraryEntry } | null>(null);

  const list = useCallback(() => {
    client.listLibrary({}).then(
      (r) => {
        setEntries(r.entries);
        setUnreadable(r.unreadable);
      },
      (e: unknown) => setError(errorMessage(e)),
    );
  }, [client]);

  // Read again when this recording's entry changes (a save, autosave, a rename).
  useEffect(list, [list, link?.id, link?.revision]);

  /** Runs a library call; a refused one says why and changes nothing here. */
  const run = async (call: () => Promise<{ session?: Session }>): Promise<boolean> => {
    setError(null);
    try {
      const response = await call();
      if (response.session) onSession(response.session);
      list();
      return true;
    } catch (e) {
      setError(errorMessage(e));
      return false;
    }
  };

  const openEntry = (entry: LibraryEntry) => void run(() => client.openFromLibrary({ id: entry.id }));
  const saveAsNew = () => {
    void run(() => client.saveToLibrary({ name: name.trim(), asNew: true })).then((saved) => {
      if (saved) setName("");
    });
  };

  return (
    <details className="library" open={open}>
      <summary>
        Library
        {link?.id ? ` · ${link.name}` : ""}
        {link?.id && link.unsaved ? " · unsaved changes" : ""}
      </summary>
      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}
      {link?.autosaveError && (
        <div className="error" role="alert">
          Autosave stopped: {link.autosaveError}
        </div>
      )}
      <div className="row">
        <label className="field" htmlFor={`${id}-name`}>
          <span>Save as</span>
          <input id={`${id}-name`} value={name} maxLength={128} placeholder="A name for this recording" onChange={(e) => setName(e.target.value)} />
        </label>
      </div>
      <div className="actions">
        <button type="button" className="btn small" disabled={busy || !name.trim() || session.steps === 0} onClick={saveAsNew}>
          Save as new
        </button>
        <button type="button" className="btn small primary" disabled={busy || !link?.id || !link.unsaved} onClick={() => void run(() => client.saveToLibrary({ asNew: false }))}>
          Save
        </button>
        <label className="check" title="Keeps the saved recording up to date. It records edits, not what ran on the device, so it cannot say what happened before a crash.">
          <input
            type="checkbox"
            checked={link?.autosave ?? false}
            disabled={busy || !link?.id}
            onChange={(e) => void run(() => client.setAutosave({ enabled: e.target.checked }))}
          />
          Autosave
        </label>
      </div>
      {entries === null ? (
        !error && <div className="empty">Reading the library…</div>
      ) : entries.length === 0 ? (
        <div className="empty">Nothing saved yet.</div>
      ) : (
        <ul className="library-list" aria-label="Saved recordings">
          {entries.map((entry) => (
            <li key={entry.id}>
              {renaming?.entry.id === entry.id ? (
                <form
                  className="actions"
                  onSubmit={(e) => {
                    e.preventDefault();
                    void run(() => client.renameInLibrary({ id: entry.id, name: renaming.name.trim(), revision: entry.revision })).then((done) => {
                      if (done) setRenaming(null);
                    });
                  }}
                >
                  <input aria-label={`New name for ${entry.name}`} className="grow" value={renaming.name} maxLength={128} onChange={(e) => setRenaming({ entry, name: e.target.value })} />
                  <button type="submit" className="btn small primary" disabled={busy || !renaming.name.trim()}>
                    Rename
                  </button>
                  <button type="button" className="btn small" onClick={() => setRenaming(null)}>
                    Cancel
                  </button>
                </form>
              ) : (
                <>
                  <span className="library-name">{entry.name}</span>
                  <span className="count">
                    {steps(entry.steps)}
                    {entry.updatedAt ? ` · ${timestampDate(entry.updatedAt).toLocaleString()}` : ""}
                  </span>
                  <span className="actions">
                    <button
                      type="button"
                      className="btn small"
                      disabled={busy}
                      aria-label={`Open ${entry.name}`}
                      onClick={() => (session.steps > 0 && link?.unsaved ? setConfirming({ kind: "open", entry }) : openEntry(entry))}
                    >
                      Open
                    </button>
                    <button type="button" className="btn small" disabled={busy} aria-label={`Rename ${entry.name}`} onClick={() => setRenaming({ entry, name: entry.name })}>
                      Rename
                    </button>
                    <button type="button" className="btn small" disabled={busy} aria-label={`Delete ${entry.name}`} onClick={() => setConfirming({ kind: "delete", entry })}>
                      Delete
                    </button>
                  </span>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {unreadable > 0 && <p className="hint">{unreadable === 1 ? "One file" : `${unreadable} files`} in the library could not be read, and were left in place.</p>}
      {confirming?.kind === "open" && (
        <ChoiceDialog
          title={`Open ${confirming.entry.name}?`}
          body={<p>It replaces the current recording, whose changes are not saved. This cannot be undone.</p>}
          choices={[
            {
              label: "Replace",
              primary: true,
              onChoose: () => {
                setConfirming(null);
                openEntry(confirming.entry);
              },
            },
          ]}
          onCancel={() => setConfirming(null)}
        />
      )}
      {confirming?.kind === "delete" && (
        <ChoiceDialog
          title={`Delete ${confirming.entry.name}?`}
          body={<p>It is removed from this machine. A recording open from it stays open, but is no longer saved.</p>}
          choices={[
            {
              label: "Delete",
              primary: true,
              onChoose: () => {
                setConfirming(null);
                void run(() => client.deleteFromLibrary({ id: confirming.entry.id, revision: confirming.entry.revision }));
              },
            },
          ]}
          onCancel={() => setConfirming(null)}
        />
      )}
    </details>
  );
}
