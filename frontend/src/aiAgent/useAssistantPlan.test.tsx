import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { FormEvent } from "react";
import { afterEach, expect, it, vi } from "vitest";
import { useAssistantPlan } from "./useAssistantPlan";
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it("invalidates old plans on context changes and ignores a late response", async () => {
  const responses: ((response: Response) => void)[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(() => new Promise<Response>((resolve) => responses.push(resolve)));
  const event = { preventDefault: vi.fn() } as unknown as FormEvent;
  const { result, rerender } = renderHook(({ message }) => useAssistantPlan(message, '{}'), { initialProps: { message: "Inspect device A" } });
  let old: Promise<void>;
  act(() => { old = result.current.submit(event); });
  rerender({ message: "Inspect device B" });
  let current: Promise<void>;
  act(() => { current = result.current.submit(event); });
  await waitFor(() => expect(responses).toHaveLength(2));
  await act(async () => { responses[1](new Response(JSON.stringify({ planId: "new-plan" }), { status: 200 })); await current; });
  expect(result.current.plan?.planId).toBe("new-plan");
  await act(async () => { responses[0](new Response(JSON.stringify({ planId: "old-plan" }), { status: 200 })); await old; });
  expect(result.current.plan?.planId).toBe("new-plan");
});

it("keeps the parent during clarification, sends only an answer and prevents duplicate requests", async () => {
  let finish: (response:Response)=>void = () => {};
  const fetch = vi.spyOn(globalThis,"fetch").mockResolvedValueOnce(new Response(JSON.stringify({planId:"parent",planningStatus:"NEEDS_INPUT",actions:[]}),{status:200}))
    .mockImplementationOnce(()=>new Promise<Response>(resolve=>{finish=resolve;}));
  const {result}=renderHook(()=>useAssistantPlan("Создай дашборд",'{}'));
  await act(async()=>{await result.current.submit({preventDefault:vi.fn()} as unknown as FormEvent);});
  let reply:Promise<boolean>;
  act(()=>{reply=result.current.clarify("Мой обзор");});
  expect(result.current.plan?.planId).toBe("parent");
  expect(result.current.isPlanning).toBe(true);
  await act(async()=>{expect(await result.current.clarify("Повтор")).toBe(false);});
  expect(fetch).toHaveBeenCalledTimes(2);
  expect(String(fetch.mock.calls[1][0])).toContain("/plans/parent/clarifications");
  expect(JSON.parse(fetch.mock.calls[1][1]!.body as string)).toEqual({message:"Мой обзор"});
  await act(async()=>{finish(new Response(JSON.stringify({planId:"child",actions:[]}),{status:200}));await reply;});
  expect(result.current.plan?.planId).toBe("child");
});

it("retains the parent on failure and ignores a clarification response after context changes",async()=>{
  let finish:(response:Response)=>void=()=>{};
  const fetch=vi.spyOn(globalThis,"fetch").mockResolvedValueOnce(new Response(JSON.stringify({planId:"parent",actions:[]}),{status:200}))
    .mockResolvedValueOnce(new Response(JSON.stringify({message:"Temporary failure"}),{status:502}))
    .mockImplementationOnce(()=>new Promise<Response>(resolve=>{finish=resolve;}));
  const {result,rerender}=renderHook(({message})=>useAssistantPlan(message,'{}'),{initialProps:{message:"Original"}});
  await act(async()=>{await result.current.submit({preventDefault:vi.fn()} as unknown as FormEvent);});
  await act(async()=>{expect(await result.current.clarify("Answer")).toBe(false);});
  expect(result.current.plan?.planId).toBe("parent");
  expect(result.current.error).toBeTruthy();
  let pending:Promise<boolean>;
  act(()=>{pending=result.current.clarify("Retry");});
  await waitFor(()=>expect(fetch).toHaveBeenCalledTimes(3));
  rerender({message:"Different task"});
  await act(async()=>{finish(new Response(JSON.stringify({planId:"obsolete"}),{status:200}));expect(await pending).toBe(false);});
  expect(result.current.plan).toBeNull();
});
