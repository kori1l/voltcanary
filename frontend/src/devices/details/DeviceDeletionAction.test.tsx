import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { DeviceDeletionAction } from "./DeviceDeletionAction";
import { requestDeviceDeletion } from "../devicesApi";
vi.mock("../devicesApi", () => ({requestDeviceDeletion: vi.fn()}));
beforeEach(() => vi.resetAllMocks());
function show() {
  render(<MemoryRouter><Routes><Route path="/" element={<DeviceDeletionAction deviceId="fixture" name="Test bus"/>}/><Route path="/devices/deletions" element={<p>Deletion progress</p>}/></Routes></MemoryRouter>);
}
it("cancels without deleting and preserves history by default", async () => {
  show(); fireEvent.click(screen.getByRole("button", {name: "Удалить устройство"}));
  expect(screen.getByLabelText("Сохранить историю удалённого устройства")).toBeChecked();
  fireEvent.click(screen.getByRole("button", {name: "Отмена"}));
  expect(requestDeviceDeletion).not.toHaveBeenCalled();
  vi.mocked(requestDeviceDeletion).mockResolvedValue({deviceId:"fixture",status:"PENDING"});
  fireEvent.click(screen.getByRole("button", {name: "Удалить устройство"}));
  fireEvent.click(screen.getByRole("button", {name: "Удалить устройство, сохранить историю"}));
  expect(await screen.findByText("Deletion progress")).toBeInTheDocument();
  expect(requestDeviceDeletion).toHaveBeenCalledWith("fixture", false);
});
it("submits explicit purge policy and keeps failed confirmation available for retry", async () => {
  vi.mocked(requestDeviceDeletion).mockRejectedValueOnce(new Error("Service unavailable"));
  show(); fireEvent.click(screen.getByRole("button", {name: "Удалить устройство"}));
  fireEvent.click(screen.getByLabelText("Удалить историю вместе с устройством"));
  fireEvent.click(screen.getByRole("button", {name: "Удалить устройство и историю"}));
  expect(await screen.findByRole("alert")).toHaveTextContent("Service unavailable");
  expect(requestDeviceDeletion).toHaveBeenCalledWith("fixture", true);
  await waitFor(() => expect(screen.getByRole("button", {name: "Удалить устройство и историю"})).toBeEnabled());
});
