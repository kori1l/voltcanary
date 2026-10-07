import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { AdminSessions } from "./AdminSessions";
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it("reviews revocation with the target user and displays only session metadata", async () => {
  let revoked = false;
  const mutations: string[] = [];
  vi.spyOn(globalThis,"fetch").mockImplementation(async (input,init) => {
    if (init?.method === "POST") { revoked = true; mutations.push(String(input)); return new Response('{"revokedCount":1}', { status: 200 }); }
    return new Response(JSON.stringify([{ id: "session-id", userId: "subject", createdAt: "2026-10-01T00:00:00Z", expiresAt: "2026-11-01T00:00:00Z", revokedAt: revoked ? "2026-10-06T00:00:00Z" : null, active: !revoked, refreshToken: "must-not-render" }]), { status: 200 });
  });
  render(<AdminSessions user={{ id: "subject", email: "subject@example.test", role: "USER", status: "ACTIVE", emailVerified: true, createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z" }} onClose={vi.fn()} />);
  await screen.findByText("Active refresh session");
  expect(screen.queryByText("must-not-render")).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Revoke all refresh sessions" }));
  const review = screen.getByRole("dialog", { name: "Revoke refresh sessions" });
  expect(review).toHaveTextContent("subject@example.test");
  expect(review).toHaveTextContent("Existing access tokens remain valid until expiry");
  await userEvent.click(within(review).getByRole("button", { name: "Cancel" }));
  expect(mutations).toHaveLength(0);
  await userEvent.click(screen.getByRole("button", { name: "Revoke all refresh sessions" }));
  await userEvent.click(within(screen.getByRole("dialog", { name: "Revoke refresh sessions" })).getByRole("button", { name: "Revoke refresh sessions" }));
  expect(await screen.findByText("Revoked refresh session")).toBeInTheDocument();
  expect(mutations).toHaveLength(1);
  expect(mutations[0]).toContain("/users/subject/sessions/revoke");
});
