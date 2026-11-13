import type {AgentPlan} from "./aiAgentApi";

export interface AssistantDefaultReply {message:string;label:string}

export function assistantDefaultReply(plan:AgentPlan|null):AssistantDefaultReply|null {
  if(plan?.planningStatus!=="NEEDS_INPUT" || plan.execution || (plan.expiresAt&&Date.parse(plan.expiresAt)<=Date.now()))return null;
  if(plan.safetyNotes.some(note=>/PROVIDER_FAILED|MODEL_OUTPUT_LIMIT|Provider request failed/.test(note)))return null;
  const question=plan.clarification;
  if(question?.requiresExplicitChoice)return null;
  if(!question?.missingFields.length||!question.suggestedValues)return null;
  const values=question.missingFields.map(field=>question.suggestedValues[field]);
  if(values.some(value=>value==null||typeof value==='object'||(typeof value==='string'&&!value.trim())))return null;
  const message=`Используй предложенные значения: ${JSON.stringify(question.suggestedValues)}. Остальные параметры сохрани.`;
  if(message.length>2000)return null;
  return {label:values.map(String).join("; "),message};
}
