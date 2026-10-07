import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ResetPasswordPage } from "./ResetPasswordPage";
import { ForgotPasswordPage } from "./ForgotPasswordPage";
import { PasswordChangeForm } from "../auth/PasswordChangeForm";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
function Location() { const location = useLocation(); return <output aria-label="Current query">{location.search}</output>; }

describe("account recovery and security", () => {
  it("accepts an incoming reset token, removes it from the displayed URL and submits the canonical payload", async () => {
    const fetch = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response(JSON.stringify({message:"Token expired"}), {status:400,headers:{"Content-Type":"application/json"}}));
    vi.stubGlobal("fetch", fetch);
    render(<MemoryRouter initialEntries={["/reset-password?token=reset-fixture"]}><ResetPasswordPage /><Location /></MemoryRouter>);
    await waitFor(() => expect(screen.getByLabelText("Current query").textContent).toBe(""));
    await userEvent.type(screen.getByLabelText(/New password/), "fixture-password");
    await userEvent.click(screen.getByRole("button", { name: "Change password" }));
    expect(JSON.parse(String(fetch.mock.calls[0][1]?.body))).toEqual({ token:"reset-fixture",newPassword:"fixture-password" });
    expect(await screen.findByRole("alert")).toHaveTextContent("expired");
    expect(screen.getByRole("link", {name:"Request a new token"})).toBeInTheDocument();
  });
  it("does not display server-provided account-existence hints during recovery", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({message:"Known user found"}), {status:200,headers:{"Content-Type":"application/json"}})));
    render(<MemoryRouter><ForgotPasswordPage /></MemoryRouter>);
    await userEvent.type(screen.getByLabelText("Email"), "fixture@example.test");
    await userEvent.click(screen.getByRole("button", {name:"Request reset token"}));
    expect(await screen.findByRole("status")).toHaveTextContent("If this email is eligible");
    expect(screen.queryByText("Known user found")).not.toBeInTheDocument();
  });
  it("prevents duplicate password-change submission and preserves values after failure", async () => {
    let release!: (value: Response) => void;
    const fetch = vi.fn(() => new Promise<Response>((resolve) => {release = resolve;}));
    vi.stubGlobal("fetch",fetch);
    render(<PasswordChangeForm />);
    await userEvent.type(screen.getByLabelText(/Current password/),"current-fixture");
    await userEvent.type(screen.getByLabelText(/New password/),"next-fixture");
    const form = screen.getByRole("button",{name:"Change password"}).closest("form")!;
    fireEvent.submit(form); fireEvent.submit(form);
    expect(fetch).toHaveBeenCalledOnce();
    release(new Response(JSON.stringify({message:"Could not change password"}),{status:503,headers:{"Content-Type":"application/json"}}));
    await screen.findByRole("alert");
    expect(screen.getByLabelText(/New password/)).toHaveValue("next-fixture");
    await userEvent.click(screen.getByRole("button",{name:"Show new password"}));
    expect(screen.getByLabelText(/New password/)).toHaveAttribute("type","text");
  });
});
