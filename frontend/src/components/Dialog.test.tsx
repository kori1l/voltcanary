import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Dialog, DialogClose } from "./Dialog";
import { ConfirmDialog } from "./ConfirmDialog";
import { EntitySelector } from "./EntitySelector";

afterEach(cleanup);

describe("console dialog workflows", () => {
  it("preserves a dirty draft on Escape until discard is explicitly selected", async () => {
    const close = vi.fn();
    render(<Dialog title="Edit device" dirty onClose={close}><input aria-label="Name" defaultValue="Kitchen fridge" /><DialogClose /></Dialog>);
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    expect(close).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Keep editing" }));
    expect(screen.getByLabelText("Name")).toHaveValue("Kitchen fridge");
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await userEvent.click(screen.getByRole("button", { name: "Discard changes" }));
    expect(close).toHaveBeenCalledOnce();
  });

  it("requires a reason and passes the reviewed value to the action", async () => {
    const confirm = vi.fn();
    render(<ConfirmDialog title="Cancel operation" object="Kitchen inspection" impact="Alert suppression will end." reasonRequired onCancel={vi.fn()} onConfirm={confirm} />);
    expect(screen.getByRole("button", { name: "Cancel operation" })).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/Reason/), "  Rescheduled  ");
    await userEvent.click(screen.getByRole("button", { name: "Cancel operation" }));
    expect(confirm).toHaveBeenCalledWith("Rescheduled");
  });

  it("searches readable identities and returns only canonical IDs", async () => {
    const change = vi.fn();
    render(<EntitySelector label="Location" options={[{ id: "uuid-kitchen", name: "Kitchen" }, { id: "uuid-office", name: "Office" }]} value="" onChange={change} />);
    await userEvent.type(screen.getByLabelText("Search location"), "kitchen");
    expect(screen.queryByRole("option", { name: "Office" })).not.toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("Location"), "uuid-kitchen");
    expect(change).toHaveBeenCalledWith("uuid-kitchen");
  });
});
