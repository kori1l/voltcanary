import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Dialog } from "../../../components/Dialog";
import {
  commandKey,
  dashboardRevisions,
  rollbackDashboardDraft,
  getDashboardDraft,
  type Breakpoint,
  type DashboardDefinition,
  type DashboardMetadata,
  type DashboardScreen,
  type RuntimeContext,
} from "../../../dashboards/dashboardsApi";
import type { DashboardEditorState } from "../hooks/useDashboardEditor";
import { DashboardManagement } from "./DashboardManagement";
import { DashboardRuntimeControls } from "./DashboardRuntimeControls";

interface Props {
  editor: DashboardEditorState;
  metadata: DashboardMetadata;
  permissions?: string[];
  active: DashboardScreen;
  canvas: DashboardDefinition;
  breakpoint: Breakpoint;
  setBreakpoint: (value: Breakpoint) => void;
  textScale: string;
  setTextScale: (value: string) => void;
  runtime: RuntimeContext;
  locked: boolean;
  editCanvas: (next: DashboardDefinition) => void;
  onError: (message: string) => void;
  onClose: () => void;
}

export function DashboardSettingsDialog({
  editor,
  metadata,
  permissions,
  active,
  canvas,
  breakpoint,
  setBreakpoint,
  textScale,
  setTextScale,
  runtime,
  locked,
  editCanvas,
  onError,
  onClose,
}: Props) {
  const [revisions, setRevisions] = useState<{ revision: number; publishedAt: string }[]>([]);
  const [historyError, setHistoryError] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    void dashboardRevisions(metadata.id)
      .then((page) => {
        if (active) setRevisions(page.items);
      })
      .catch(() => {
        if (active)
          setHistoryError("Published history is unavailable. Retry by reopening settings.");
      });
    return () => {
      active = false;
    };
  }, [metadata.id]);
  return (
    <Dialog title="Dashboard settings" side onClose={onClose}>
      <Link to="/dashboards">All dashboards</Link>
      {historyError ? <p role="status">{historyError}</p> : null}
      <DashboardManagement
        id={metadata.id}
        name={editor.definition.name}
        published={metadata.publishedRevision > 0}
        disabled={locked || editor.dirty}
        permissions={permissions}
      />
      <p>
        Saved draft · Editing {active.title} · {breakpoint} layout. Publish updates the viewing
        version; choose the personal start page from the published dashboard.
      </p>{" "}
      <fieldset disabled={locked}>
        <legend>Dashboard metadata and presentation</legend>
        <label>
          Responsive layout
          <select
            value={breakpoint}
            onChange={(event) => setBreakpoint(event.target.value as Breakpoint)}
          >
            <option value="desktop">Desktop</option>
            <option value="tablet">Tablet</option>
            <option value="mobile">Mobile</option>
          </select>
        </label>
        <label>
          Dashboard name
          <input
            maxLength={80}
            required
            value={editor.definition.name}
            onChange={(event) => editor.edit({ ...editor.definition, name: event.target.value })}
          />
        </label>
        <label>
          Dashboard description
          <textarea
            maxLength={500}
            value={editor.definition.description ?? ""}
            onChange={(event) =>
              editor.edit({ ...editor.definition, description: event.target.value })
            }
          />
        </label>
        <label>
          Preview text size
          <select value={textScale} onChange={(event) => setTextScale(event.target.value)}>
            <option value="100">100%</option>
            <option value="200">200%</option>
          </select>
        </label>
      </fieldset>
      <DashboardRuntimeControls
        key={JSON.stringify(runtime)}
        context={runtime}
        disabled={locked}
        onDefaults={(defaultContext) => editor.edit({ ...editor.definition, defaultContext })}
      />
      <h3>Sections on {active.title}</h3>
      <fieldset disabled={locked}>
        <button
          className="ghost-button"
          disabled={editor.definition.sections.length >= 24}
          onClick={() =>
            editCanvas({
              ...canvas,
              sections: [
                ...canvas.sections,
                {
                  id: commandKey(),
                  title: `Section ${canvas.sections.length + 1}`,
                  widgetIds: [],
                  screenId: active.id,
                },
              ],
            })
          }
        >
          Add section
        </button>
        {canvas.sections.map((section) => (
          <div key={section.id}>
            <label>
              Section title
              <input
                maxLength={120}
                value={section.title}
                onChange={(event) =>
                  editCanvas({
                    ...canvas,
                    sections: canvas.sections.map((item) =>
                      item.id === section.id ? { ...item, title: event.target.value } : item,
                    ),
                  })
                }
              />
            </label>
            <button
              className="ghost-button"
              onClick={() =>
                editCanvas({
                  ...canvas,
                  sections: canvas.sections.filter((item) => item.id !== section.id),
                })
              }
            >
              Remove section {section.title}
            </button>
          </div>
        ))}
      </fieldset>{" "}
      {revisions.length ? (
        <details>
          <summary>Published history and rollback as draft</summary>
          {revisions.map((revision) => (
            <button
              className="ghost-button"
              key={revision.revision}
              disabled={locked || editor.dirty}
              onClick={() =>
                void rollbackDashboardDraft(editor.draft, revision.revision)
                  .then(() => getDashboardDraft(metadata.id))
                  .then(() => editor.resolve(false))
                  .catch((error) =>
                    onError(error instanceof Error ? error.message : "Rollback failed"),
                  )
              }
            >
              Review revision {revision.revision} as draft · {revision.publishedAt}
            </button>
          ))}
        </details>
      ) : null}
      <p>
        Drag a panel header to move it or pull an edge to resize. Keyboard placement and panel
        actions are in panel settings в†’ Layout. Each responsive layout is independent.
      </p>
    </Dialog>
  );
}
