import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { Tabs } from "./Tabs";
import { Field } from "./Field";
import { MaintenanceAgenda } from "../maintenance/MaintenanceAgenda";
import type { MaintenanceOperation } from "../maintenance/maintenanceApi";

describe("accessible workflow controls", () => {
  it("moves focus without activating heavy panels, then activates with Enter", async () => {
    const activated = vi.fn();
    function View() { const [value, setValue] = useState("a"); return <Tabs label="Evidence" items={[{value:"a",label:"Overview"},{value:"b",label:"History"},{value:"c",label:"Disabled",disabled:true}]} value={value} onChange={(next) => { activated(next); setValue(next); }}>{value}</Tabs>; }
    render(<View />);
    const overview = screen.getByRole("tab", {name:"Overview"});
    const history = screen.getByRole("tab", {name:"History"});
    await userEvent.click(overview); activated.mockClear();
    await userEvent.keyboard("{ArrowRight}");
    expect(history).toHaveFocus(); expect(overview).toHaveAttribute("aria-selected", "true"); expect(activated).not.toHaveBeenCalled();
    await userEvent.keyboard("{Enter}");
    expect(history).toHaveAttribute("aria-selected", "true");
    const panel = screen.getByRole("tabpanel");
    expect(panel.id).toBe(history.getAttribute("aria-controls"));
    expect(panel).toHaveAttribute("aria-labelledby", history.id);
    await userEvent.keyboard("{End}"); expect(history).toHaveFocus();
    await userEvent.keyboard("{Home}"); expect(overview).toHaveFocus();
  });
  it("associates both hint and error with the required input", () => {
    render(<Field label="Name" required hint="Use a recognisable name" error="Name is required">{(props) => <input {...props} />}</Field>);
    const input = screen.getByRole("textbox", {name:"Name"});
    expect(input).toHaveAttribute("aria-required","true");
    expect(input).toHaveAttribute("aria-invalid","true");
    expect(input).toHaveAccessibleDescription("Use a recognisable name Name is required");
    expect(screen.getAllByRole("alert")).toHaveLength(1);
  });
  it("Enter on Cancel does not select the maintenance operation", async () => {
    const onSelect = vi.fn(); const onCancel = vi.fn();
    const operation = {id:"operation",title:"Long refrigerator inspection",status:"SCHEDULED",devices:[],startAt:"2026-10-08T10:00:00Z",endAt:"2026-10-08T11:00:00Z"} as unknown as MaintenanceOperation;
    render(<MaintenanceAgenda operations={[operation]} selectedOperationId={null} onSelect={onSelect} onCancel={onCancel} onFinish={vi.fn()} isBusy={false} />);
    await userEvent.tab(); await userEvent.tab(); await userEvent.keyboard("{Enter}");
    expect(onCancel).toHaveBeenCalledWith(operation); expect(onSelect).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button",{name:"Open maintenance Long refrigerator inspection"}));
    expect(onSelect).toHaveBeenCalledWith("operation");
  });
});
