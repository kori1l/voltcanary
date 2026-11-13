import {expect,it} from "vitest";
import {assistantDefaultReply} from "./assistantDefaultReply";
import type {AgentPlan} from "./aiAgentApi";

const plan:AgentPlan={planId:"task",summary:"Question",explanation:"Как назвать дашборд?",questions:["Как назвать дашборд?"],planningStatus:"NEEDS_INPUT",actions:[],confirmationRequired:false,safetyNotes:[]};
it("does not infer a default from words in a question",()=>{
  expect(assistantDefaultReply(plan)).toBeNull();
});
it("does not automatically choose an ambiguous source",()=>{
  expect(assistantDefaultReply({...plan,clarification:{missingFields:["sourceId"],suggestedReply:"source A",suggestionLabel:"Source A",suggestedValues:{sourceId:"source-A"},requiresExplicitChoice:true}})).toBeNull();
});
it("uses model-proposed values for any declared missing field without domain-specific guesses",()=>{
  expect(assistantDefaultReply({...plan,clarification:{missingFields:["chartStyle"],suggestedReply:"Используй ступенчатый график",suggestionLabel:"Ступенчатый график",suggestedValues:{chartStyle:"step"}}}))
    .toEqual({message:'Используй предложенные значения: {"chartStyle":"step"}. Остальные параметры сохрани.',label:"step"});
});
it("does not offer defaults on provider failure",()=>{
  expect(assistantDefaultReply({...plan,safetyNotes:["PROVIDER_FAILED"],clarification:{missingFields:["name"],suggestedReply:"Use name",suggestionLabel:"Name",suggestedValues:{name:"Name"}}})).toBeNull();
});
it("requires concrete values rather than automatically sending another question",()=>{
  expect(assistantDefaultReply({...plan,clarification:{missingFields:["name"],suggestedReply:"Как назвать?",suggestionLabel:"Укажите имя",suggestedValues:{}}})).toBeNull();
});
