import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AdminWorkspace } from "./AdminWorkspace";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const operator = { id:"operator",email:"admin@example.test",role:"ADMIN",status:"ACTIVE",emailVerified:true };
const user = { id:"subject",email:"subject@example.test",role:"USER",status:"ACTIVE",emailVerified:true,createdAt:"2026-10-01T00:00:00Z",updatedAt:"2026-10-01T00:00:00Z" };

describe("explicit administrator access changes", () => {
  it("keeps role drafts local, cancels without mutation and submits only a reviewed change", async () => {
    const requests: {path:string;method:string;body?:unknown}[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: string, init?:RequestInit) => {
      const path = new URL(input).pathname;
      requests.push({path,method:init?.method ?? "GET",body:init?.body ? JSON.parse(String(init.body)):undefined});
      return new Response(JSON.stringify(path === "/auth/me" ? operator : path.endsWith("/role") ? {...user,role:"SUPPORT"} : [user]),{status:200,headers:{"Content-Type":"application/json"}});
    }));
    render(<MemoryRouter><AdminWorkspace /></MemoryRouter>);
    await userEvent.selectOptions(await screen.findByLabelText("Role for subject@example.test"),"SUPPORT");
    expect(requests.filter((request)=>request.method !== "GET")).toHaveLength(0);
    await userEvent.click(screen.getByRole("button",{name:"Apply role"}));
    expect(await screen.findByRole("dialog")).toHaveTextContent("USER to SUPPORT");
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button",{name:"Cancel"}));
    expect(requests.filter((request)=>request.method !== "GET")).toHaveLength(0);
    await userEvent.click(screen.getByRole("button",{name:"Apply role"}));
    await userEvent.click(within(await screen.findByRole("dialog")).getByRole("button",{name:"Apply role change"}));
    await waitFor(()=>expect(requests).toContainEqual({path:"/auth/admin/users/subject/role",method:"PATCH",body:{role:"SUPPORT"}}));
    expect(await screen.findByRole("status")).toHaveTextContent("role changed to SUPPORT");
  });

  it.each(["USER","SUPPORT"])("does not load administration data for %s",async(role)=>{
    const fetch = vi.fn(async()=>new Response(JSON.stringify({...operator,role}),{status:200,headers:{"Content-Type":"application/json"}}));
    vi.stubGlobal("fetch",fetch);
    render(<MemoryRouter><AdminWorkspace /></MemoryRouter>);
    await screen.findByText("Administrator access required");
    expect(fetch).toHaveBeenCalledOnce();
    expect(screen.queryByRole("button",{name:"Refresh users"})).not.toBeInTheDocument();
  });
});
