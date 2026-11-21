import {act,cleanup,fireEvent,render,screen,waitFor} from "@testing-library/react";
import {afterEach,expect,it,vi} from "vitest";
import {AssistantTraceProgress,AssistantTurnTrace} from "./AssistantTurnTrace";
import {clearTokens} from "../api/client";
import {exportAssistantTrace} from "./assistantTraceApi";

afterEach(()=>{cleanup();vi.restoreAllMocks();vi.unstubAllGlobals();vi.useRealTimers();});
const event={id:"event",schemaVersion:1,sequence:1,conversationId:"chat",messageId:"message",turnId:"turn",requestId:"request",attemptId:"attempt",stage:"SANITIZATION",status:"FAILED",code:"UNSUPPORTED_FIELDS",durationMs:4,details:{fieldPaths:["dataRefs"],candidate:{apiKey:"[redacted]"}},truncated:true,createdAt:"2026-10-25T00:00:00Z"};

it("recovers the recorded consultation state from the turn trace",async()=>{
  const consultation={...event,stage:"CONSULTATION",status:"OBSERVED",code:"WAITING_USER",
    details:{taskId:"saved-task",taskRevision:2,exchanges:1}};
  const fetch=vi.spyOn(globalThis,"fetch").mockImplementation(async()=>new Response(JSON.stringify({items:[consultation],nextCursor:null})));
  const view=render(<AssistantTurnTrace conversationId="chat" turnId="turn"/>);
  fireEvent.click(screen.getByText("Этапы запроса"));
  expect(await screen.findByText("Консультация: Ожидает ответа")).toBeInTheDocument();
  view.unmount();
  render(<AssistantTurnTrace conversationId="chat" turnId="turn"/>);
  fireEvent.click(screen.getByText("Этапы запроса"));
  expect(await screen.findByText("Консультация: Ожидает ответа")).toBeInTheDocument();
  expect(fetch.mock.calls.every(([,options])=>(options?.method??"GET")==="GET")).toBe(true);
});

it("loads only observed stages on demand and keeps rejected fields in expandable technical details",async()=>{
  const fetch=vi.spyOn(globalThis,"fetch").mockResolvedValue(new Response(JSON.stringify({items:[event],nextCursor:null})));
  render(<AssistantTurnTrace conversationId="chat" turnId="turn"/>);
  expect(fetch).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText("Этапы запроса"));
  expect(await screen.findByText("Проверка полей: Ошибка")).toBeInTheDocument();
  expect(screen.getByText("UNSUPPORTED_FIELDS")).toBeInTheDocument();
  expect(screen.getByText(/Подробности сокращены/)).toBeInTheDocument();
  expect(screen.getByText(/dataRefs/)).toBeInTheDocument();
  expect(String(fetch.mock.calls[0][0])).toContain("/conversations/chat/turns/turn/trace");
  expect(screen.getByRole("button",{name:"Экспорт диагностики"})).toBeEnabled();
});

it("does not turn missing diagnostics into a guessed successful stage",async()=>{
  vi.spyOn(globalThis,"fetch").mockResolvedValue(new Response(JSON.stringify({items:[],nextCursor:null})));
  render(<AssistantTraceProgress conversationId="chat" turnId="turn" phase="planning"/>);
  await waitFor(()=>expect(screen.getByRole("status")).toHaveTextContent("Подготавливаю запрос…"));
  expect(screen.queryByText(/Завершён/)).not.toBeInTheDocument();
});

it("downloads the authenticated export without calling planning or execution",async()=>{
  vi.useFakeTimers();
  const create=vi.fn(()=>"blob:trace"),revoke=vi.fn();
  vi.stubGlobal("URL",class extends URL {static createObjectURL=create;static revokeObjectURL=revoke;});
  vi.spyOn(HTMLAnchorElement.prototype,"click").mockImplementation(()=>{});
  const fetch=vi.spyOn(globalThis,"fetch").mockResolvedValue(new Response(JSON.stringify([event]),{headers:{"Content-Type":"application/json"}}));
  await exportAssistantTrace("chat","turn");
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(String(fetch.mock.calls[0][0])).toContain("/conversations/chat/turns/turn/trace/export");
  expect(create).toHaveBeenCalledTimes(1);
  vi.runAllTimers();
});

it("uses the actual emitted failure for progress and ignores a late response after identity changes",async()=>{
  let finish:(response:Response)=>void=()=>{};
  vi.spyOn(globalThis,"fetch").mockImplementation(()=>new Promise(resolve=>{finish=resolve;}));
  const view=render(<AssistantTraceProgress conversationId="chat" turnId="turn" phase="planning"/>);
  act(()=>clearTokens());
  await act(async()=>{finish(new Response(JSON.stringify({items:[event],nextCursor:null})));});
  expect(screen.getByRole("status")).toHaveTextContent("Подготавливаю запрос…");
  view.unmount();
  vi.spyOn(globalThis,"fetch").mockResolvedValue(new Response(JSON.stringify({items:[event],nextCursor:null})));
  render(<AssistantTraceProgress conversationId="chat" turnId="turn" phase="planning"/>);
  expect(await screen.findByText("Проверка полей: Ошибка")).toBeInTheDocument();
});
