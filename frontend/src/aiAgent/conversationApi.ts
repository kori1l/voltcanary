import {apiRequest,prepareAiSession} from "../api/client";
import type {AgentPlan, DashboardTurnMetadata} from "./aiAgentApi";

export interface SavedConversation {id:string;title:string;revision:number;lastPlanId:string|null;createdAt:string;updatedAt:string;expiresAt:string}
export interface SavedMessage {id:string;sequence:number;requestId:string;role:"user"|"assistant";kind:string;text:string;redacted:boolean;planId?:string;turnId?:string;outcome?:string}
export interface ConversationPage<T> {items:T[];nextCursor:string|null}
export interface OrchestrationProgress {version:"assistant-v2";planId:string;revision:number;status:string;steps:{stepId:string;intentId:string;status:string;dependsOn:string[];diagnostic?:{category:string;code:string;message:string}|null}[]}
export interface ConversationDetail {conversation:SavedConversation;plan:AgentPlan|null;resourceStatus:string;pendingStatus:string|null;orchestration?:OrchestrationProgress;acceptedReview?:AgentPlan}
export interface SubmittedMessage {requestId:string;expectedRevision:number;text:string;context:Record<string,unknown>;parentPlanId?:string;metadata:DashboardTurnMetadata;automatic:boolean;displayText?:string}
const path=(id:string)=>`/ai-agent/conversations/${encodeURIComponent(id)}`;
export async function listConversations(before?:string) {
  const result=await apiRequest<{conversations:ConversationPage<SavedConversation>;activeConversationId:string|null}>(`/ai-agent/conversations${before?`?before=${encodeURIComponent(before)}`:""}`);
  if(!Array.isArray(result?.conversations?.items))throw new Error("Не удалось загрузить историю разговоров.");
  return result;
}
export const createConversation=(conversationId:string)=>apiRequest<SavedConversation>("/ai-agent/conversations",{method:"POST",body:JSON.stringify({conversationId})});
export const selectConversation=(id:string)=>apiRequest<SavedConversation>(path(id),{method:"PATCH"});
export const getConversation=(id:string)=>apiRequest<ConversationDetail>(path(id));
export async function submitConversationMessage(id:string,body:SubmittedMessage) {
  await prepareAiSession();
  return apiRequest<ConversationDetail>(`${path(id)}/messages`,{method:"POST",body:JSON.stringify(body)});
}
export async function controlOrchestration(conversationId:string,action:"CANCEL"|"RETRY"|"RESUME",stepId:string|null=null) {
  const detail=await getConversation(conversationId);
  const current=detail.orchestration;
  if(!current)throw new Error("План ещё не сохранён. Обновите разговор.");
  return apiRequest(`/ai-agent/orchestrations/${encodeURIComponent(current.planId)}/control`,{method:"POST",body:JSON.stringify({
    version:"assistant-v2",kind:"control.request",requestId:crypto.randomUUID(),expectedRevision:current.revision,action,stepId
  })});
}
export async function conversationMessages(id:string) {
  const items:SavedMessage[]=[];
  let after="0";
  do {
    const page=await apiRequest<ConversationPage<SavedMessage>>(`${path(id)}/messages?after=${after}`);
    items.push(...page.items);
    if(!page.nextCursor)break;
    if(Number(page.nextCursor)<=Number(after))throw new Error("Не удалось прочитать порядок сообщений.");
    after=page.nextCursor;
  }while(true);
  return items;
}
