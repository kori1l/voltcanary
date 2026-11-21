import {act, cleanup, renderHook, waitFor} from "@testing-library/react";
import {afterEach, expect, it, vi} from "vitest";
import {clearTokens, setTokens} from "../api/client";
import {mockAssistantFetch} from "../test/assistantConversationServer";
import {useServerConversation} from "./useServerConversation";
import {readConversationDraft, saveConversationDraft} from "./conversationDrafts";

const login=(owner="history-owner")=>setTokens(`header.${btoa(JSON.stringify({sub:owner}))}.fixture`,"refresh");
const response={planId:"saved",summary:"Review bus22",explanation:"Проверьте bus22",confirmationRequired:true,planningStatus:"READY",executionStatus:"READY",safetyNotes:[],actions:[{id:"create",tool:"CREATE_DEVICE",title:"bus22",description:"Vehicle",requiresConfirmation:true,parameters:{name:"bus22"}}]};
afterEach(()=>{cleanup();clearTokens();localStorage.clear();vi.restoreAllMocks();});

it.each([200,409])("controls only the displayed plan revision and refreshes on HTTP %s",async(status)=>{
  login();
  mockAssistantFetch().mockResolvedValue(new Response(JSON.stringify(response)));
  const route=vi.mocked(globalThis.fetch).getMockImplementation()!;
  const writes:{url:string;body:unknown}[]=[];
  let replaced=false;
  vi.mocked(globalThis.fetch).mockImplementation(async(input,options)=>{
    const url=String(input);
    if(url.endsWith("/control")){
      writes.push({url,body:JSON.parse(String(options?.body))});
      return Response.json({}, {status});
    }
    const result=await route(input,options);
    if((options?.method??"GET")==="GET"&&url.includes("/ai-agent/conversations/")){
      const body=await result.clone().json();
      if(body.conversation)return Response.json({...body,orchestration:{
        version:"assistant-v2",planId:replaced?"new-plan":"displayed-plan",
        revision:replaced?8:3,status:"ACTIVE",steps:[],
      }});
    }
    return result;
  });
  const hook=renderHook(()=>useServerConversation());
  await waitFor(()=>expect(hook.result.current.busy).toBe(false));
  await act(()=>hook.result.current.send("Создай устройство"));
  await act(()=>hook.result.current.reload());
  expect(hook.result.current.conversation.orchestration?.planId).toBe("displayed-plan");
  replaced=true;
  await act(()=>hook.result.current.control("CANCEL"));
  expect(writes).toEqual([{
    url:expect.stringContaining("/orchestrations/displayed-plan/control"),
    body:expect.objectContaining({expectedRevision:3,action:"CANCEL"}),
  }]);
  expect(hook.result.current.conversation.orchestration?.planId).toBe("new-plan");
  if(status===409)expect(hook.result.current.error).toContain("Состояние плана обновлено");
});

it("sends a saved name question through the typed answer endpoint",async()=>{
  login();
  const domain=mockAssistantFetch().mockImplementation(async()=>new Response(JSON.stringify(response)));
  const route=vi.mocked(globalThis.fetch).getMockImplementation()!;
  let answered=false;
  const writes:unknown[]=[];
  const snapshot={revision:4,question:{id:"question",revision:2,slots:["slot"],choices:[]},slots:{slot:{schemaId:"text-v1"}}};
  vi.mocked(globalThis.fetch).mockImplementation(async(input,options)=>{
    const url=String(input);
    if(url.endsWith("/requirements"))return new Response(JSON.stringify(snapshot));
    if(url.endsWith("/requirements/answers")){
      writes.push(JSON.parse(String(options?.body)));
      answered=true;
      return new Response(JSON.stringify({...snapshot,question:null}));
    }
    const result=await route(input,options);
    if((options?.method??"GET")==="GET"&&url.includes("/ai-agent/conversations/")){
      const body=await result.clone().json();
      if(body.conversation)return new Response(JSON.stringify({...body,
        plan:{...response,clarification:answered?null:{missingFields:["name"]}},
        orchestration:{version:"assistant-v2",planId:"root",revision:answered?6:4,status:answered?"ACTIVE":"WAITING_INPUT",
          pendingQuestion:answered?null:{questionId:"question"},steps:[]}}));
    }
    return result;
  });
  const hook=renderHook(()=>useServerConversation());
  await waitFor(()=>expect(hook.result.current.busy).toBe(false));
  await act(()=>hook.result.current.send("Создай дашборд"));
  await act(()=>hook.result.current.reload());
  await act(()=>hook.result.current.send("Скорость"));
  expect(writes).toEqual([expect.objectContaining({planId:"root",questionId:"question",expectedQuestionRevision:2,
    expectedRequirementsRevision:4,answer:{kind:"TEXT",text:"Скорость"}})]);
  expect(domain).toHaveBeenCalledTimes(1);
  expect(hook.result.current.conversation.orchestration?.pendingQuestion).toBeNull();
});

it("restores the server accepted dashboard preview after a failed edit using GET only",async()=>{
  login();
  const domain=mockAssistantFetch().mockResolvedValue(new Response(JSON.stringify({...response,planningStatus:"FAILED",actions:[]})));
  const route=vi.mocked(globalThis.fetch).getMockImplementation()!;
  let pending=false;
  const accepted={...response,planId:"accepted-dashboard",actions:[{id:"dashboard",tool:"CREATE_DASHBOARD",title:"Dashboard",description:"Review",requiresConfirmation:true,parameters:{definition:{name:"Accepted",widgets:[]}}}]};
  vi.mocked(globalThis.fetch).mockImplementation(async(input,options)=>{
    const result=await route(input,options);
    const url=String(input);
    if((options?.method??"GET")==="GET"&&url.includes("/ai-agent/conversations/")){
      const body=await result.clone().json();
      if(body.conversation)return new Response(JSON.stringify({...body,...(pending?{plan:null,pendingStatus:"PENDING"}:{}),acceptedReview:accepted}));
    }
    return result;
  });
  const first=renderHook(()=>useServerConversation());await waitFor(()=>expect(first.result.current.busy).toBe(false));
  await act(()=>first.result.current.send("Измени выбранный дашборд"));
  first.unmount();
  const restored=renderHook(()=>useServerConversation());await waitFor(()=>expect(restored.result.current.busy).toBe(false));
  expect(restored.result.current.conversation.plan?.planningStatus).toBe("FAILED");
  expect(restored.result.current.conversation.retainedPreview?.planId).toBe("accepted-dashboard");
  expect(restored.result.current.conversation.allowAutoReply).toBe(false);
  pending=true;
  await act(()=>restored.result.current.reload());
  expect(restored.result.current.conversation.pendingStatus).toBe("PENDING");
  expect(restored.result.current.conversation.plan).toBeNull();
  expect(restored.result.current.conversation.retainedPreview?.planId).toBe("accepted-dashboard");
  expect(domain).toHaveBeenCalledTimes(1);
});

it("restores complete ordered history, active conversation and a separate draft using GET only",async()=>{
  login();const domain=mockAssistantFetch().mockResolvedValue(new Response(JSON.stringify(response)));
  const first=renderHook(()=>useServerConversation());await waitFor(()=>expect(first.result.current.busy).toBe(false));
  await act(()=>first.result.current.send("Создай bus22"));
  act(()=>first.result.current.patch({draft:"Добавь карту"}));
  const before=first.result.current.conversation;
  first.unmount();
  const restored=renderHook(()=>useServerConversation());await waitFor(()=>expect(restored.result.current.busy).toBe(false));
  expect(restored.result.current.conversation.id).toBe(before.id);
  expect(restored.result.current.conversation.turns).toEqual(before.turns);
  expect(restored.result.current.conversation.draft).toBe("Добавь карту");
  expect(restored.result.current.conversation.allowAutoReply).toBe(false);
  expect(domain).toHaveBeenCalledTimes(1);
});

it("preserves a second tab's draft while refreshing a stale revision without submitting it again",async()=>{
  login();const domain=mockAssistantFetch().mockResolvedValue(new Response(JSON.stringify(response)));
  const first=renderHook(()=>useServerConversation());await waitFor(()=>expect(first.result.current.busy).toBe(false));
  await act(()=>first.result.current.newConversation());
  const second=renderHook(()=>useServerConversation());await waitFor(()=>expect(second.result.current.busy).toBe(false));
  await act(()=>first.result.current.send("Создай bus22"));
  act(()=>second.result.current.patch({draft:"Моя правка"}));
  await act(()=>second.result.current.send());
  expect(second.result.current.error).toBeTruthy();
  expect(second.result.current.conversation.draft).toBe("Моя правка");
  expect(second.result.current.conversation.turns).toHaveLength(2);
  expect(domain).toHaveBeenCalledTimes(1);
});

it("restores a pending submission without invoking the provider again",async()=>{
  login();let finish:(value:Response)=>void=()=>{};
  const domain=mockAssistantFetch().mockImplementation(()=>new Promise(resolve=>{finish=resolve;}));
  const first=renderHook(()=>useServerConversation());await waitFor(()=>expect(first.result.current.busy).toBe(false));
  let sending:Promise<void>;
  act(()=>{sending=first.result.current.send("Создай bus22");});
  await waitFor(()=>expect(domain).toHaveBeenCalledTimes(1));
  const restored=renderHook(()=>useServerConversation());await waitFor(()=>expect(restored.result.current.busy).toBe(false));
  expect(restored.result.current.conversation.pendingStatus).toBe("PENDING");
  expect(restored.result.current.conversation.turns).toHaveLength(1);
  await act(async()=>{finish(new Response(JSON.stringify(response)));await sending;});
  await act(()=>restored.result.current.reload());
  expect(restored.result.current.conversation.turns).toHaveLength(2);
  expect(domain).toHaveBeenCalledTimes(1);
});

it("keeps conversations independent and restores each unsent draft",async()=>{
  login();mockAssistantFetch().mockResolvedValue(new Response(JSON.stringify(response)));
  const hook=renderHook(()=>useServerConversation());await waitFor(()=>expect(hook.result.current.busy).toBe(false));
  await act(()=>hook.result.current.send("Первый bus22"));const id=hook.result.current.conversation.id;
  act(()=>hook.result.current.patch({draft:"Правка первого"}));
  await act(()=>hook.result.current.newConversation({},"Второй запрос"));
  expect(hook.result.current.conversation.turns).toHaveLength(0);
  await act(()=>hook.result.current.select(id));
  expect(hook.result.current.conversation.draft).toBe("Правка первого");
  expect(hook.result.current.conversation.turns[0].text).toBe("Первый bus22");
});

it("clears visible history and ignores a late response after logout",async()=>{
  login();let finish:(value:Response)=>void=()=>{};
  mockAssistantFetch().mockImplementation(()=>new Promise(resolve=>{finish=resolve;}));
  const hook=renderHook(()=>useServerConversation());await waitFor(()=>expect(hook.result.current.busy).toBe(false));
  let sending:Promise<void>;
  act(()=>{sending=hook.result.current.send("Создай bus22");});
  await waitFor(()=>expect(hook.result.current.conversation.saved).toBe(true));
  act(()=>clearTokens());
  await act(async()=>{finish(new Response(JSON.stringify(response)));await sending;});
  expect(hook.result.current.conversation.turns).toHaveLength(0);
  expect(hook.result.current.history).toHaveLength(0);
});

it("redacts local drafts and scopes them to their authenticated owner",()=>{
  login("owner-a");saveConversationDraft("chat","bus22 password=hunter2 Bearer abc.xyz");
  expect(readConversationDraft("chat")).toBe("bus22 password=[redacted] [redacted]");
  expect(JSON.stringify(localStorage)).not.toContain("hunter2");
  login("owner-b");expect(readConversationDraft("chat")).toBe("");
  login("owner-a");expect(readConversationDraft("chat")).toContain("bus22");
});

it("does not store recognized provider keys or bare token assignments in drafts",()=>{
  login();
  const google="AIza"+"x".repeat(35),source="AQ."+"x".repeat(35);
  saveConversationDraft("chat",`bus token=plain-secret ${google} ${source}`);
  expect(readConversationDraft("chat")).not.toContain("plain-secret");
  expect(readConversationDraft("chat")).not.toContain(google);
  expect(readConversationDraft("chat")).not.toContain(source);
});

it("retains the dashboard task after failed preparation so a short name reply cannot become device creation",async()=>{
  login();
  const domain=mockAssistantFetch().mockImplementation(async()=>new Response(JSON.stringify({
    ...response,planningStatus:"FAILED",actions:[],summary:"Не удалось подготовить изменения",clarification:null,
    task:{taskId:"failed-task",branchId:"failed-task",revision:1,status:"FAILED"}
  })));
  const hook=renderHook(()=>useServerConversation());await waitFor(()=>expect(hook.result.current.busy).toBe(false));
  await act(()=>hook.result.current.send("Создай для этого автобуса дашборд"));
  await act(()=>hook.result.current.send("Назови Мой любимый трамвай"));
  const [url,init]=domain.mock.calls[1];
  expect(url).toBe("http://localhost:8080/ai-agent/plans/saved/clarifications");
  expect(JSON.parse(String(init?.body)).taskId).toBe("failed-task");
});
