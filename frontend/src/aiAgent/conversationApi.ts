import {apiRequest,prepareAiSession} from "../api/client";
import type {AgentPlan, DashboardTurnMetadata} from "./aiAgentApi";

export interface SavedConversation {id:string;title:string;revision:number;lastPlanId:string|null;createdAt:string;updatedAt:string;expiresAt:string}
export interface SavedMessage {id:string;sequence:number;requestId:string;role:"user"|"assistant";kind:string;text:string;redacted:boolean;planId?:string;turnId?:string;outcome?:string}
export interface ConversationPage<T> {items:T[];nextCursor:string|null}
export interface OrchestrationProgress {version:"assistant-v2";planId:string;revision:number;status:string;pendingQuestion?:{questionId:string}|null;steps:{stepId:string;intentId:string;status:string;dependsOn:string[];diagnostic?:{category:string;code:string;message:string}|null}[]}
export interface RequirementSnapshot {
  revision: number;
  question: {id: string; revision: number; slots: string[]; choices: unknown[]} | null;
  slots: Record<string, {schemaId: string}>;
}
export const getRequirements = (planId: string) =>
  apiRequest<RequirementSnapshot>(`/ai-agent/plans/${encodeURIComponent(planId)}/requirements`);
export async function answerRequirement(planId: string, snapshot: RequirementSnapshot, requestId: string, text: string) {
  const question = snapshot.question;
  if (!question) throw new Error("Уточнение больше недоступно.");
  await prepareAiSession();
  return apiRequest<RequirementSnapshot>(`/ai-agent/plans/${encodeURIComponent(planId)}/requirements/answers`, {
    method: "POST",
    body: JSON.stringify({planId, requestId, questionId: question.id, expectedQuestionRevision: question.revision,
      expectedRequirementsRevision: snapshot.revision, answer: {kind: "TEXT", text}}),
  });
}
export interface ConversationDetail {conversation:SavedConversation;plan:AgentPlan|null;resourceStatus:string;pendingStatus:string|null;orchestration?:OrchestrationProgress;acceptedReview?:AgentPlan}
export interface AssistantConsultation {
  id: string;
  revision: number;
  status: "QUEUED" | "RUNNING" | "WAITING_DATA" | "WAITING_CHILD" | "WAITING_USER" |
    "SUCCEEDED" | "FAILED" | "CANCELLED" | "STALE" | "EXPIRED";
}
export const listAssistantConsultations = (planId: string) =>
  apiRequest<AssistantConsultation[]>(`/ai-agent/plans/${encodeURIComponent(planId)}/consultations`);
export function cancelAssistantConsultation(planId: string, task: AssistantConsultation, requestKey: string) {
  return apiRequest<AssistantConsultation>(
    `/ai-agent/plans/${encodeURIComponent(planId)}/consultations/${encodeURIComponent(task.id)}/cancel`,
    {method: "POST", body: JSON.stringify({expectedTaskRevision: task.revision, requestKey})},
  );
}
export interface SubmittedMessage {requestId:string;expectedRevision:number;text:string;context:Record<string,unknown>;parentPlanId?:string;metadata:DashboardTurnMetadata;automatic:boolean;displayText?:string;continueStepId?:string}
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
export async function controlOrchestration(plan:Pick<OrchestrationProgress,"planId"|"revision">,action:"CANCEL"|"RETRY"|"RESUME",stepId:string|null=null) {
  return apiRequest(`/ai-agent/orchestrations/${encodeURIComponent(plan.planId)}/control`,{method:"POST",body:JSON.stringify({
    version:"assistant-v2",kind:"control.request",requestId:crypto.randomUUID(),expectedRevision:plan.revision,action,stepId
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
