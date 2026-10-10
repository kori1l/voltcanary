import {cleanup,fireEvent,render,screen} from "@testing-library/react";
import {afterEach,expect,it,vi} from "vitest";
import {DashboardGridSettings} from "./DashboardGridSettings";
afterEach(cleanup);
it("makes responsive profiles and editing-only guides explicit",()=>{
 const profile=vi.fn(),guides=vi.fn(),compact=vi.fn();render(<DashboardGridSettings breakpoint="desktop" guides disabled={false} onBreakpoint={profile} onGuides={guides} onCompact={compact}/>);
 expect(screen.getByLabelText("12 columns")).toBeVisible();expect(screen.getByRole("spinbutton",{name:"Row height (px)"})).toHaveValue(48);
 fireEvent.change(screen.getByRole("combobox",{name:"Layout"}),{target:{value:"mobile"}});expect(profile).toHaveBeenCalledWith("mobile");fireEvent.click(screen.getByRole("checkbox",{name:"Show grid guides"}));expect(guides).toHaveBeenCalledWith(false);fireEvent.click(screen.getByRole("button",{name:"Compact this layout"}));expect(compact).toHaveBeenCalledOnce();
});
it("locks layout changes during saves and conflicts",()=>{
 render(<DashboardGridSettings breakpoint="mobile" guides={false} disabled onBreakpoint={vi.fn()} onGuides={vi.fn()} onCompact={vi.fn()}/>);expect(screen.getByRole("combobox")).toBeDisabled();expect(screen.getByRole("button",{name:"Compact this layout"})).toBeDisabled();
});

it("applies exact numeric settings and blocks out-of-range values",()=>{
 const save=vi.fn();render(<DashboardGridSettings breakpoint="desktop" guides disabled={false} settings={{columns:12,rowHeight:48,gap:12}} onSettings={save} onBreakpoint={vi.fn()} onGuides={vi.fn()} onCompact={vi.fn()}/>);
 fireEvent.change(screen.getByRole("spinbutton",{name:"Columns"}),{target:{value:"24"}});fireEvent.change(screen.getByRole("spinbutton",{name:"Row height (px)"}),{target:{value:"32"}});fireEvent.change(screen.getByRole("spinbutton",{name:"Spacing (px)"}),{target:{value:"6"}});fireEvent.click(screen.getByRole("button",{name:"Apply grid size"}));expect(save).toHaveBeenCalledWith({columns:24,rowHeight:32,gap:6});
 fireEvent.change(screen.getByRole("spinbutton",{name:"Columns"}),{target:{value:"49"}});expect(screen.getByRole("button",{name:"Apply grid size"})).toBeDisabled();
});
