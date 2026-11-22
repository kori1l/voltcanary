import { Settings2, Plus, Undo2, Redo2, Eye, Grid2X2 } from "lucide-react";
import { Link } from "react-router-dom";
import { SidebarToggle } from "../../../components/NavigationVisibility";
import type { DashboardEditorState } from "../hooks/useDashboardEditor";

interface Props {
  editor: DashboardEditorState;
  dashboardId: string;
  screenId: string;
  locked: boolean;
  catalogAvailable: boolean;
  paletteOpen: boolean;
  canPublish: boolean;
  confirmationBusy: boolean;
  publicationBlocked: boolean;
  setScreenToolbar: (node: HTMLDivElement | null) => void;
  onGridSettings: () => void;
  onTogglePalette: () => void;
  onSettings: () => void;
  reviewPublication: () => Promise<void>;
}

export function DashboardEditorToolbar({
  editor,
  dashboardId,
  screenId,
  locked,
  catalogAvailable,
  paletteOpen,
  canPublish,
  confirmationBusy,
  publicationBlocked,
  setScreenToolbar,
  onGridSettings,
  onTogglePalette,
  onSettings,
  reviewPublication,
}: Props) {
  return (
    <header className="dashboard-workspace-header">
      <div className="dashboard-workspace-title">
        <SidebarToggle />
        <h1>{editor.definition.name}</h1>
        <span className="dashboard-save-state" role="status">
          {editor.status}
        </span>
        <span className="sr-only">
          Edit private draft · base revision {editor.draft.baseRevision}
        </span>
      </div>
      <div className="dashboard-header-links">
        <div ref={setScreenToolbar} className="dashboard-screen-slot" />{" "}
        <fieldset className="dashboard-editor-actions" disabled={locked}>
          <div className="action-row">
            <button className="ghost-button" onClick={onGridSettings} aria-label="Grid settings">
              <Grid2X2 size={16} /> Grid
            </button>
            <button
              className="primary-button"
              disabled={!catalogAvailable || editor.definition.widgets.length >= 24}
              onClick={onTogglePalette}
              aria-expanded={paletteOpen}
              aria-label="Add widget"
            >
              <Plus size={16} /> Panel
            </button>
            <button
              className="ghost-button"
              disabled={!editor.canUndo}
              onClick={editor.undo}
              aria-label="Undo"
              title="Undo"
            >
              <Undo2 size={16} />
            </button>
            <button
              className="ghost-button"
              disabled={!editor.canRedo}
              onClick={editor.redo}
              aria-label="Redo"
              title="Redo"
            >
              <Redo2 size={16} />
            </button>
            <button
              className="ghost-button"
              onClick={() => void editor.save()}
              aria-label="Save draft now"
            >
              Save
            </button>
            {canPublish ? (
              <button
                className="primary-button"
                disabled={!editor.definition.name.trim() || editor.dirty || publicationBlocked}
                onClick={() => void reviewPublication()}
                aria-label="Review publication"
              >
                {confirmationBusy ? "CheckingвЂ¦" : "Publish"}
              </button>
            ) : null}
          </div>
        </fieldset>
        <Link
          to={`/dashboards/${dashboardId}?screenId=${screenId}`}
          aria-label="View published dashboard"
          title="View published dashboard"
        >
          <Eye size={16} />
        </Link>
        <button
          className="ghost-button"
          onClick={onSettings}
          aria-label="Dashboard settings"
          title="Dashboard settings"
        >
          <Settings2 size={16} />
        </button>
      </div>
    </header>
  );
}
