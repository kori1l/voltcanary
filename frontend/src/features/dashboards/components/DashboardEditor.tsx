import { DashboardEditorToolbar } from "./DashboardEditorToolbar";
import { DashboardSettingsDialog } from "./DashboardSettingsDialog";
import { DashboardWidgetConfigurationDialog } from "./DashboardWidgetConfigurationDialog";
import { visualizationLabels, type Visualization } from "../model/dashboardVisualization";
import { resizeScreenGrid } from "../model/dashboardGridResize";
import { workingGrid } from "../model/dashboardGrid";
import { compactGridLayout } from "../model/dashboardGridCompaction";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { Pencil } from "lucide-react";
import { Link, useNavigate } from "react-router-dom";
import { SidebarToggle } from "../../../components/NavigationVisibility";
import { Dialog } from "../../../components/Dialog";
import { useConsoleQuery } from "../../../app/useConsoleQuery";
import { sessionIdentityRevision } from "../../../api/client";
import { useDashboardQuery } from "../../../dashboards/useDashboardQuery";
import {
  listWidgetTypes,
  validateDashboardQuery,
  type WidgetManifest,
} from "../../../dashboards/dashboardQueriesApi";
import {
  commandKey,
  createDashboard,
  type Breakpoint,
  type DashboardDefinition,
  type DashboardDraft,
  type DashboardMetadata,
} from "../../../dashboards/dashboardsApi";
import { useDashboardEditor } from "../hooks/useDashboardEditor";
import { widgetDefaultSize, registerManifestSizes } from "../model/dashboardLayout";
import {
  dashboardScreens,
  replaceScreenContent,
  screenDefinition,
} from "../model/dashboardScreens";
import { DashboardWidgetPalette } from "./DashboardWidgetPalette";
import { insertWidget } from "../model/dashboardWidgetInsertion";
import type { Placement, DashboardGrid } from "../../../dashboards/dashboardsApi";
import { DashboardGridSettings } from "./DashboardGridSettings";
import { DashboardCanvas } from "./DashboardCanvas";
import { runtimeFromUrl } from "./DashboardRuntimeControls";
import { DashboardScreenNavigation } from "./DashboardScreenNavigation";

export function DashboardEditor({
  metadata,
  initial,
  definitionReadyAt,
  permissions,
}: {
  metadata: DashboardMetadata;
  initial: DashboardDraft;
  definitionReadyAt?: number;
  permissions?: string[];
}) {
  const [interactiveMs, setInteractiveMs] = useState<number | null>(null);
  useLayoutEffect(() => {
    if (definitionReadyAt !== undefined) {
      const end = performance.now();
      setInteractiveMs(end - definitionReadyAt);
      if (typeof performance.measure === "function")
        performance.measure("voltcanary.dashboard.editor-interactive", {
          start: definitionReadyAt,
          end,
        });
    }
  }, [definitionReadyAt]);
  const editor = useDashboardEditor(metadata, initial),
    navigate = useNavigate(),
    query = useConsoleQuery();
  const canPublish = permissions === undefined || permissions.includes("publish");
  const [manifests, setManifests] = useState<WidgetManifest[]>([]),
    [catalogError, setCatalogError] = useState<string | null>(null);
  const [screenToolbar, setScreenToolbar] = useState<HTMLDivElement | null>(null);
  const [dragVisualization, setDragVisualization] = useState<Visualization | undefined>();
  const [paletteOpen, setPaletteOpen] = useState(false),
    [dragType, setDragType] = useState<string | null>(null);
  const [gridOpen, setGridOpen] = useState(false),
    [showGuides, setShowGuides] = useState(true);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [configured, setConfigured] = useState<string | null>(null);
  const [publishOpen, setPublishOpen] = useState(false),
    [confirmationBusy, setConfirmationBusy] = useState(false),
    [commandError, setCommandError] = useState<string | null>(null);
  const [breakpoint, setBreakpoint] = useState<Breakpoint>(
      window.innerWidth >= 1200 ? "desktop" : window.innerWidth >= 768 ? "tablet" : "mobile",
    ),
    [textScale, setTextScale] = useState("100"),
    [announcement, setAnnouncement] = useState("");
  useEffect(() => {
    const responsive = () =>
      setBreakpoint(
        window.innerWidth >= 1200 ? "desktop" : window.innerWidth >= 768 ? "tablet" : "mobile",
      );
    window.addEventListener("resize", responsive);
    return () => window.removeEventListener("resize", responsive);
  }, []);
  const copyLock = useRef(false),
    copyKey = useRef<{ body: string; key: string } | null>(null);
  const screenFocus = useRef<{ id: string; screenId: string } | null>(null);
  const screens = dashboardScreens(editor.definition),
    requestedScreen = query.params.get("screenId"),
    active = screens.find((screen) => screen.id === requestedScreen) ?? screens[0];
  const canvas = screenDefinition(editor.definition, active.id),
    runtime = runtimeFromUrl(query.params, editor.definition.defaultContext);
  useEffect(() => {
    const pending = screenFocus.current;
    if (pending && pending.screenId === active.id && active.widgetIds.includes(pending.id)) {
      screenFocus.current = null;
      document.querySelector<HTMLElement>(`[data-dashboard-widget="${pending.id}"]`)?.focus();
    }
  }, [active.id, active.widgetIds]);
  const evidence = useDashboardQuery(
    metadata.id,
    {
      draftEtag: editor.draft.etag,
      runtimeContext: runtime,
      widgetIds: canvas.widgets.map((widget) => widget.id),
    },
    canvas.widgets.length > 0 &&
      !editor.dirty &&
      !editor.recovery &&
      !editor.conflict &&
      !editor.denied,
    active.id,
  );
  const busy = editor.status === "Saving" || confirmationBusy,
    locked = busy || editor.conflict || !!editor.recovery;
  useEffect(() => {
    const controller = new AbortController();
    void listWidgetTypes(controller.signal)
      .then((items) => {
        if (!controller.signal.aborted) {
          registerManifestSizes(items);
          setManifests(items);
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted)
          setCatalogError(error instanceof Error ? error.message : "Catalog unavailable");
      });
    return () => controller.abort();
  }, [metadata.id]);
  function editCanvas(next: DashboardDefinition) {
    editor.edit(replaceScreenContent(editor.definition, active.id, next));
  }
  function focusWidget(id: string) {
    window.requestAnimationFrame(() =>
      document.querySelector<HTMLElement>(`[data-dashboard-widget="${id}"]`)?.focus(),
    );
  }
  function addFromPalette(
    type: string,
    drop?: { positions: Placement[]; temporaryId: string; sectionId?: string },
    visualization?: Visualization,
  ) {
    if (
      locked ||
      editor.definition.widgets.length >= 24 ||
      (["configured-records", "rule-window-result"].includes(type) &&
        editor.definition.defaultContext.scope?.workspaceId)
    )
      return;
    const chosen = manifests.find((m) => m.type === type && m.supported);
    if (!chosen) return;
    const id = commandKey();
    const collaboration = editor.definition.defaultContext.scope?.workspaceId
      ? {
          workspaceId: editor.definition.defaultContext.scope.workspaceId,
          accountId: editor.definition.defaultContext.scope.accountId,
        }
      : {};
    const added = {
      id,
      type,
      typeVersion: chosen.typeVersion,
      title: visualization
        ? `${visualizationLabels[visualization]} · ${chosen.title}`
        : chosen.title,
      binding: {
        ...collaboration,
        mode: [
          "latest-value",
          "time-series",
          "metric-summary",
          "vehicle-map",
          "vehicle-value",
          "vehicle-timeline",
        ].includes(type)
          ? ("UNBOUND" as const)
          : ("DYNAMIC" as const),
      },
      query: { rows: 20 },
      presentation: visualization ? { visualization } : {},
      navigation: {},
      payload: {},
    };
    editCanvas(insertWidget(canvas, added, breakpoint, drop));
    setDragType(null);
    setPaletteOpen(false);
    setConfigured(id);
    setAnnouncement(`Added ${chosen.title} on ${active.title}.`);
  }
  function resizeGrid(settings: DashboardGrid) {
    if (!locked) editCanvas(resizeScreenGrid(canvas, breakpoint, settings));
  }
  function compactLayout() {
    if (!locked) editCanvas(compactGridLayout(canvas, breakpoint));
  }
  async function reviewPublication() {
    if (!canPublish || locked || editor.dirty) return;
    setConfirmationBusy(true);
    setCommandError(null);
    const identity = sessionIdentityRevision();
    try {
      const validation = await validateDashboardQuery(metadata.id, {
        draftEtag: editor.draft.etag,
        runtimeContext: runtime,
      });
      if (identity !== sessionIdentityRevision()) return;
      if (!Array.isArray(validation.panels))
        throw new Error("Publication validation is unavailable.");
      const blocked = validation.panels.filter((panel) =>
        ["invalid", "no_access", "unavailable"].includes(panel.status),
      );
      if (blocked.length)
        throw new Error(
          `${blocked.length} widgets across the screens need configuration or renewed access before publication.`,
        );
      setPublishOpen(true);
    } catch (error) {
      if (identity === sessionIdentityRevision())
        setCommandError(error instanceof Error ? error.message : "Publication validation failed");
    } finally {
      setConfirmationBusy(false);
    }
  }
  async function copyLocal() {
    if (copyLock.current) return;
    copyLock.current = true;
    setCommandError(null);
    const identity = sessionIdentityRevision();
    try {
      const definition = {
          ...editor.definition,
          name: `${editor.definition.name.slice(0, 65)} recovered copy`,
        },
        body = JSON.stringify(definition);
      if (copyKey.current?.body !== body) copyKey.current = { body, key: commandKey() };
      const copy = await createDashboard(definition, copyKey.current.key);
      if (identity === sessionIdentityRevision()) {
        editor.discard();
        window.requestAnimationFrame(() => {
          if (identity === sessionIdentityRevision()) navigate(`/dashboards/${copy.id}/edit`);
        });
      }
    } catch (error) {
      if (identity === sessionIdentityRevision())
        setCommandError(error instanceof Error ? error.message : "Copy failed");
    } finally {
      copyLock.current = false;
    }
  }
  const widget = editor.definition.widgets.find((item) => item.id === configured),
    manifest =
      widget &&
      manifests.find(
        (item) => item.type === widget.type && item.typeVersion === widget.typeVersion,
      );
  if (editor.denied)
    return (
      <>
        <SidebarToggle />
        <p role="alert">Dashboard access is unavailable. Local recovery was cleared.</p>
        <Link to="/dashboards">All dashboards</Link>
      </>
    );
  return (
    <div
      data-editor-interactive-ms={interactiveMs?.toFixed(2)}
      className={
        textScale === "200" ? "dashboard-editor dashboard-editor-large" : "dashboard-editor"
      }
    >
      <DashboardEditorToolbar
        editor={editor}
        dashboardId={metadata.id}
        screenId={active.id}
        locked={locked}
        catalogAvailable={manifests.length > 0}
        paletteOpen={paletteOpen}
        canPublish={canPublish}
        confirmationBusy={confirmationBusy}
        publicationBlocked={!!evidence.error || (canvas.widgets.length > 0 && !evidence.data)}
        setScreenToolbar={setScreenToolbar}
        onGridSettings={() => setGridOpen(true)}
        onTogglePalette={() => setPaletteOpen((open) => !open)}
        onSettings={() => setSettingsOpen(true)}
        reviewPublication={reviewPublication}
      />
      {commandError || catalogError ? <p role="alert">{commandError ?? catalogError}</p> : null}
      {requestedScreen && active.id !== requestedScreen ? (
        <p role="status">Requested screen is unavailable. Showing {active.title}.</p>
      ) : null}
      {editor.recovery ? (
        <section className="panel">
          <h2>Recover unsaved metadata</h2>
          <p>
            A safe draft for this user, account, dashboard and base revision is available. Query
            values are never stored.
          </p>
          <button className="ghost-button" onClick={editor.restoreRecovery}>
            Review recovered draft
          </button>
          <button className="ghost-button" onClick={editor.dismissRecovery}>
            Discard recovered draft
          </button>
        </section>
      ) : null}
      {editor.conflict ? (
        <section className="panel" role="alert">
          <h2>Concurrent revision conflict</h2>
          <p>Your local screens are preserved. Review the latest server draft before reapplying.</p>
          {editor.latestConflict ? (
            <dl>
              <dt>Latest server draft</dt>
              <dd>
                {editor.latestConflict.definition.name} · base {editor.latestConflict.baseRevision}{" "}
                · {dashboardScreens(editor.latestConflict.definition).length} screens
              </dd>
              <dt>Your local draft</dt>
              <dd>
                {editor.definition.name} · {screens.length} screens
              </dd>
              <dt>Changed fields</dt>
              <dd>
                {(
                  [
                    "name",
                    "description",
                    "widgets",
                    "layouts",
                    "sections",
                    "screens",
                    "defaultContext",
                  ] as const
                )
                  .filter(
                    (field) =>
                      JSON.stringify(editor.definition[field]) !==
                      JSON.stringify(editor.latestConflict!.definition[field]),
                  )
                  .join(", ") || "None"}
              </dd>
            </dl>
          ) : (
            <p>Loading latest draft for comparisonвЂ¦</p>
          )}
          <button className="ghost-button" onClick={() => void editor.resolve(false)}>
            Load latest and discard local changes
          </button>
          <button
            className="ghost-button"
            disabled={!editor.latestConflict}
            onClick={() => void editor.resolve(true)}
          >
            Reapply reviewed local draft to latest
          </button>
          <button className="ghost-button" onClick={() => void copyLocal()}>
            Create independent copy of local draft
          </button>
        </section>
      ) : null}

      {evidence.error ? (
        <p role="alert">
          Preview unavailable: {evidence.error}
          <button className="ghost-button" onClick={() => void evidence.refresh()}>
            Retry preview
          </button>
        </p>
      ) : null}
      <div aria-live="polite" className="sr-only">
        {announcement}
      </div>
      <div className={`dashboard-editor-canvas-row ${paletteOpen ? "dashboard-palette-open" : ""}`}>
        <div className="dashboard-editor-canvas-main">
          <DashboardScreenNavigation
            definition={editor.definition}
            activeId={active.id}
            onSelect={(screenId) => query.update({ screenId })}
            onEdit={editor.edit}
            disabled={locked}
            toolbarTarget={screenToolbar}
          >
            <h2 className="sr-only">{active.title}</h2>
            {!canvas.widgets.length ? (
              <div className="dashboard-empty-screen">
                <h3>Build {active.title} for the work you do</h3>
                <p>
                  Add a number, chart, table or note. Choose its position before configuration. Add
                  Monitoring, Analytics or Incidents screens with their own fields using Add screen.
                </p>
              </div>
            ) : null}
            <DashboardCanvas
              showGuides={showGuides}
              paletteType={dragType}
              paletteSize={
                dragType
                  ? widgetDefaultSize(
                      dragType,
                      breakpoint,
                      1,
                      workingGrid(canvas, breakpoint).columns,
                    )
                  : undefined
              }
              onWidgetDrop={(type, positions, temporaryId, sectionId) =>
                addFromPalette(type, { positions, temporaryId, sectionId }, dragVisualization)
              }
              definition={canvas}
              panels={editor.dirty ? [] : (evidence.data?.panels ?? [])}
              stale={evidence.status === "stale"}
              previewBreakpoint={breakpoint}
              movementDisabled={locked}
              onLayoutCommit={(positions, bp) => {
                const ids = new Set(positions.map((p) => p.widgetId));
                editCanvas({
                  ...canvas,
                  layouts: {
                    ...canvas.layouts,
                    [bp]: [
                      ...(canvas.layouts[bp] ?? []).filter((p) => !ids.has(p.widgetId)),
                      ...positions,
                    ],
                  },
                });
                setAnnouncement(`Updated ${bp} layout on ${active.title}.`);
              }}
              onRetry={() => void evidence.refresh()}
              tools={(id) => {
                const item = canvas.widgets.find((value) => value.id === id)!,
                  supported = manifests.find(
                    (value) => value.type === item.type && value.typeVersion === item.typeVersion,
                  )?.supported;
                return (
                  <fieldset className="dashboard-widget-tools" disabled={locked}>
                    <legend className="sr-only">Editing {item.title}</legend>
                    <div className="action-row">
                      <button
                        className="ghost-button"
                        onClick={() => setConfigured(id)}
                        aria-label={`Configure ${item.title}`}
                        title="Edit panel"
                      >
                        <Pencil size={16} /> Edit
                      </button>
                    </div>
                    {!supported ? (
                      <p>
                        {manifests.length
                          ? "Unsupported panel retained; layout and management remain available in settings."
                          : "Loading widget catalogвЂ¦"}
                      </p>
                    ) : null}
                  </fieldset>
                );
              }}
            />
          </DashboardScreenNavigation>
        </div>
        {paletteOpen ? (
          <DashboardWidgetPalette
            manifests={
              editor.definition.defaultContext.scope?.workspaceId
                ? manifests.filter(
                    (item) => !["configured-records", "rule-window-result"].includes(item.type),
                  )
                : manifests
            }
            disabled={locked}
            limitReached={editor.definition.widgets.length >= 24}
            onClose={() => {
              setPaletteOpen(false);
              setDragType(null);
            }}
            onAdd={(type, visualization) => addFromPalette(type, undefined, visualization)}
            onDragType={(type, visualization) => {
              setDragType(type);
              setDragVisualization(visualization);
            }}
          />
        ) : null}
      </div>
      {gridOpen ? (
        <Dialog title="Grid settings" side onClose={() => setGridOpen(false)}>
          <DashboardGridSettings
            key={`${active.id}:${breakpoint}`}
            settings={workingGrid(canvas, breakpoint)}
            onSettings={resizeGrid}
            breakpoint={breakpoint}
            guides={showGuides}
            disabled={locked}
            onBreakpoint={setBreakpoint}
            onGuides={setShowGuides}
            onCompact={compactLayout}
          />
        </Dialog>
      ) : null}
      {settingsOpen ? (
        <DashboardSettingsDialog
          editor={editor}
          metadata={metadata}
          permissions={permissions}
          active={active}
          canvas={canvas}
          breakpoint={breakpoint}
          setBreakpoint={setBreakpoint}
          textScale={textScale}
          setTextScale={setTextScale}
          runtime={runtime}
          locked={locked}
          editCanvas={editCanvas}
          onError={setCommandError}
          onClose={() => setSettingsOpen(false)}
        />
      ) : null}
      {widget && manifest ? (
        <DashboardWidgetConfigurationDialog
          editor={editor}
          canvas={canvas}
          widget={widget}
          manifest={manifest}
          breakpoint={breakpoint}
          screens={screens}
          active={active}
          locked={locked}
          editCanvas={editCanvas}
          setConfigured={setConfigured}
          focusWidget={focusWidget}
          onScreenChange={(screenId) => query.update({ screenId })}
        />
      ) : null}
      {publishOpen ? (
        <Dialog
          title={
            editor.definition.defaultContext.scope?.workspaceId
              ? "Publish team dashboard"
              : "Publish private dashboard"
          }
          busy={confirmationBusy}
          onClose={() => setPublishOpen(false)}
        >
          <p>
            {editor.definition.name}: {screens.length} screens, {editor.definition.widgets.length}{" "}
            widgets. Every screen was checked for configuration and access.
          </p>
          <ul>
            {screens.map((screen) => (
              <li key={screen.id}>
                {screen.title} · {screen.widgetIds.length} widgets
              </li>
            ))}
          </ul>
          <p>
            Publish creates an immutable revision. Preview snapshot:{" "}
            {evidence.data?.asOf ?? "No active-screen measurements"}. Inactive screen facts were not
            queried; unavailable panels remain explicit.
          </p>
          <button
            className="primary-button"
            disabled={confirmationBusy || editor.dirty}
            onClick={() => {
              setConfirmationBusy(true);
              void editor
                .publish()
                .then((success) => {
                  if (success) {
                    setPublishOpen(false);
                    navigate(`/dashboards/${metadata.id}?screenId=${active.id}`);
                  }
                })
                .finally(() => setConfirmationBusy(false));
            }}
          >
            Confirm publish
          </button>
        </Dialog>
      ) : null}
    </div>
  );
}
