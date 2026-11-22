import { Dialog } from "../../../components/Dialog";
import {
  commandKey,
  type DashboardDefinition,
  type DashboardScreen,
  type Breakpoint,
} from "../../../dashboards/dashboardsApi";
import type { WidgetManifest } from "../../../dashboards/dashboardQueriesApi";
import type { DashboardEditorState } from "../hooks/useDashboardEditor";
import {
  moveWidget,
  shiftWidget,
  resizeWidget,
  placeRelative,
  normalizeLayouts,
  removeWidget,
} from "../model/dashboardLayout";
import { transferWidget } from "../model/dashboardScreens";
import { DashboardPanelLayoutSettings } from "./DashboardPanelLayoutSettings";
import { WidgetConfiguration } from "./WidgetConfiguration";

interface Props {
  editor: DashboardEditorState;
  canvas: DashboardDefinition;
  widget: DashboardDefinition["widgets"][number];
  manifest: WidgetManifest;
  breakpoint: Breakpoint;
  screens: DashboardScreen[];
  active: DashboardScreen;
  locked: boolean;
  editCanvas: (next: DashboardDefinition) => void;
  setConfigured: (id: string | null) => void;
  focusWidget: (id: string) => void;
  onScreenChange: (id: string) => void;
}

export function DashboardWidgetConfigurationDialog({
  editor,
  canvas,
  widget,
  manifest,
  breakpoint,
  screens,
  active,
  locked,
  editCanvas,
  setConfigured,
  focusWidget,
  onScreenChange,
}: Props) {
  return (
    <Dialog title={`Configure ${widget.title}`} onClose={() => setConfigured(null)} side>
      <WidgetConfiguration
        key={widget.id}
        layoutControls={
          <DashboardPanelLayoutSettings
            definition={canvas}
            id={widget.id}
            breakpoint={breakpoint}
            screens={screens}
            screenId={active.id}
            disabled={locked}
            limitReached={editor.definition.widgets.length >= 24}
            onMove={(direction) => {
              editCanvas(
                direction === "up" || direction === "down"
                  ? moveWidget(canvas, widget.id, direction === "up" ? -1 : 1, breakpoint)
                  : shiftWidget(canvas, widget.id, direction === "left" ? -1 : 1, breakpoint),
              );
            }}
            onResize={(w, h) => editCanvas(resizeWidget(canvas, widget.id, w, h, breakpoint))}
            onPlace={(anchor, direction) =>
              editCanvas(placeRelative(canvas, widget.id, anchor, direction, breakpoint))
            }
            onScreen={(screenId) => {
              editor.edit(normalizeLayouts(transferWidget(editor.definition, widget.id, screenId)));
              onScreenChange(screenId);
            }}
            onSection={(sectionId) =>
              editCanvas(
                normalizeLayouts({
                  ...canvas,
                  sections: canvas.sections.map((section) => ({
                    ...section,
                    widgetIds: [
                      ...section.widgetIds.filter((value) => value !== widget.id),
                      ...(section.id === sectionId ? [widget.id] : []),
                    ],
                  })),
                }),
              )
            }
            onDuplicate={() => {
              const duplicate = {
                ...widget,
                id: commandKey(),
                title: `${widget.title.slice(0, 110)} copy`,
              };
              editCanvas(normalizeLayouts({ ...canvas, widgets: [...canvas.widgets, duplicate] }));
              setConfigured(duplicate.id);
            }}
            onDelete={() => {
              editCanvas(removeWidget(canvas, widget.id));
              setConfigured(null);
            }}
          />
        }
        widget={widget}
        manifest={manifest}
        onCancel={() => setConfigured(null)}
        onSave={(next) => {
          editor.edit({
            ...editor.definition,
            widgets: editor.definition.widgets.map((item) => (item.id === next.id ? next : item)),
          });
          setConfigured(null);
          focusWidget(next.id);
        }}
      />
    </Dialog>
  );
}
