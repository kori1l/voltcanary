import {cleanup,fireEvent,render,screen,waitFor} from "@testing-library/react";
import {afterEach,expect,it,vi} from "vitest";
import {DeviceInventoryControls} from "./DeviceInventoryControls";
afterEach(cleanup);
it("keeps compact primary filters visible and opens paged location lookup only on demand",async()=>{
 const locations=vi.fn().mockResolvedValue([{id:"room",name:"Kitchen"}]),select=vi.fn(),clear=vi.fn();
 render(<DeviceInventoryControls search="" onSearch={vi.fn()} location="all" locations={[]} loadLocations={locations} onLocation={select} status="all" onStatus={vi.fn()} view="cards" onView={vi.fn()} onClear={clear} scope="owned" refresh={vi.fn()}/>);
 expect(screen.getByRole("combobox",{name:"Filter by registration status"})).toBeVisible();expect(screen.getByLabelText("Inventory view")).toBeVisible();expect(locations).not.toHaveBeenCalled();expect(screen.queryByRole("textbox",{name:"Search filter by location"})).not.toBeInTheDocument();
 fireEvent.click(screen.getByRole("button",{name:"Filter by location: All locations"}));await waitFor(()=>expect(screen.getByRole("combobox",{name:"Filter by location"})).toBeEnabled());
 fireEvent.change(screen.getByRole("combobox",{name:"Filter by location"}),{target:{value:"room"}});expect(select).toHaveBeenCalledWith("room");expect(screen.queryByRole("combobox",{name:"Filter by location"})).not.toBeInTheDocument();
});
