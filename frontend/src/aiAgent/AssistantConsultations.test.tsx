import {render, screen, waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {expect, it, vi} from "vitest";
import {AssistantConsultations} from "./AssistantConsultations";
import {cancelAssistantConsultation, listAssistantConsultations} from "./conversationApi";

vi.mock("./conversationApi", () => ({
  cancelAssistantConsultation: vi.fn(),
  listAssistantConsultations: vi.fn(),
}));

it("recovers task status and reuses the cancellation key after a lost response", async () => {
  const task = {id: "task", revision: 7, status: "WAITING_USER"} as const;
  const cancelled = {...task, revision: 8, status: "CANCELLED"} as const;
  vi.mocked(listAssistantConsultations).mockResolvedValueOnce([task]).mockResolvedValueOnce([task])
    .mockResolvedValueOnce([cancelled]);
  vi.mocked(cancelAssistantConsultation).mockRejectedValueOnce(new Error("Lost response"))
    .mockResolvedValueOnce(cancelled);
  const user = userEvent.setup();
  render(<AssistantConsultations planId="plan" revision={5}/>);
  expect(await screen.findByText("Ожидает ответа")).toBeInTheDocument();
  const button = screen.getByRole("button", {name: "Отменить консультацию"});
  await user.click(button);
  await screen.findByText(/Ответ на запрос отмены недоступен/);
  await waitFor(() => expect(button).toBeEnabled());
  await user.click(button);
  expect(await screen.findByText("Отменена")).toBeInTheDocument();
  const calls = vi.mocked(cancelAssistantConsultation).mock.calls;
  expect(calls).toHaveLength(2);
  expect(calls[0]?.slice(0, 2)).toEqual(["plan", task]);
  expect(calls[1]).toEqual(calls[0]);
});
