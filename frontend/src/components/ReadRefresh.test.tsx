import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import {useState} from "react";
import {useReadRefresh} from "./useReadRefresh";
import {Dialog} from "./Dialog";
import {ReadRefreshControl} from "./ReadRefresh";
import { ReadRefresh } from "./ReadRefresh";
import { EvidenceAge } from "./EvidenceAge";

afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); localStorage.clear(); });
it("updates evidence age without a read request", () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date("2026-10-08T00:00:00Z"));
  render(<EvidenceAge value="2026-10-08T00:00:00Z" />);
  expect(screen.getByText(/0s ago/)).toBeInTheDocument();
  act(() => vi.advanceTimersByTime(30_000));
  expect(screen.getByText(/30s ago/)).toBeInTheDocument();
});
it("polls reads with backoff, pauses hidden views, and checks authorization on resume", async () => {
  vi.useFakeTimers(); vi.spyOn(Math, "random").mockReturnValue(0);
  let hidden = false;
  vi.spyOn(document, "hidden", "get").mockImplementation(() => hidden);
  localStorage.setItem("voltcanary.accessToken", "fixture");
  const refresh = vi.fn().mockResolvedValueOnce(false).mockResolvedValue(true);
  render(<ReadRefresh scope="A" refresh={refresh} />);
  fireEvent.change(screen.getByLabelText("Read refresh"), { target: { value: "30000" } });
  await act(() => vi.advanceTimersByTimeAsync(30_000));
  expect(refresh).toHaveBeenCalledTimes(1);
  await act(() => vi.advanceTimersByTimeAsync(30_000));
  expect(refresh).toHaveBeenCalledTimes(1);
  await act(() => vi.advanceTimersByTimeAsync(30_000));
  expect(refresh).toHaveBeenCalledTimes(2);
  hidden = true; fireEvent(document, new Event("visibilitychange"));
  await act(() => vi.advanceTimersByTimeAsync(300_000));
  expect(refresh).toHaveBeenCalledTimes(2);
  localStorage.clear(); hidden = false; fireEvent(document, new Event("visibilitychange"));
  await act(() => vi.advanceTimersByTimeAsync(30_000));
  expect(refresh).toHaveBeenCalledTimes(2);
});

it("continues refreshing after its settings drawer closes",async()=>{
 vi.useFakeTimers();vi.spyOn(Math,"random").mockReturnValue(0);vi.spyOn(document,"hidden","get").mockReturnValue(false);localStorage.setItem("voltcanary.accessToken","fixture");const refresh=vi.fn().mockResolvedValue(true);
 function Viewer(){const [interval,onChange]=useState(0),[open,setOpen]=useState(true);useReadRefresh({scope:"viewer",refresh,interval});return open?<Dialog title="Dashboard settings" onClose={()=>setOpen(false)}><ReadRefreshControl interval={interval} onChange={onChange}/></Dialog>:<p>Canvas</p>;}
 render(<Viewer/>);fireEvent.change(screen.getByLabelText("Read refresh"),{target:{value:"30000"}});fireEvent.click(screen.getByRole("button",{name:"Close"}));expect(screen.queryByRole("combobox")).not.toBeInTheDocument();await act(()=>vi.advanceTimersByTimeAsync(30000));expect(refresh).toHaveBeenCalledOnce();
});
