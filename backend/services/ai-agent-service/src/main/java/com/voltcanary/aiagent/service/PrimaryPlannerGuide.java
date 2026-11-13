package com.voltcanary.aiagent.service;

/** Routing/graph instructions, separate from domain operation generation. */
public final class PrimaryPlannerGuide {
    public static final String VERSION = "planner-routing-v8";
    private PrimaryPlannerGuide() { }
    public static String text() {
        return VERSION + "\n"
                + "Responsibility boundary: choose WHICH workflow and authorized resource, never whether a domain value such as a name/period/style is sufficient. "
                + "When the workflow and activeSubject are known, dispatch the selected skill even if a domain value needs interpretation; that skill owns domain questions. "
                + "Do not turn a supplied value into an ASK target question. Domain text is passed verbatim to the skill, outside planner inputs. "
                + "inputs contains authorized resource bindings from catalogue.inputKeys only; name, period, widgetKeys and commands belong to the skill. "
                + "Use advertised skill operations to recognize supported requests even when a domain property is absent from inputKeys. inputs=[] is valid for an edit of the accepted subject. "
                + "Resolve short follow-ups from exact dialogue and activeSubject; a CONTEXT_RESOURCE device is data context, not a competing dashboard subject. "
                + "ASK only about genuinely unresolved workflow/resource identity. Never ask domain questions or ask to repeat supplied values. "
                + "UNSUPPORTED means no registered workflow supports the operation, not a missing domain parameter or a WAITING_CONFIRMATION step. "
                + "Write explanation/question in the user's language. "
                + "A dashboard for an existing device needs no CREATE_DEVICE/CREATE_DATA_SOURCE step. "
                + "Use activeSubject/resourceChoices.requiredIntent: REFINE_DASHBOARD_REVIEW for an unexecuted review, UPDATE_DASHBOARD_DRAFT after execution. "
                + "CREATE_DASHBOARD is for a new dashboard, without the ambient review as its base. "
                + "PLAN is for any first step in an empty plan, including refinement/update of an explicitly imported authorized review. "
                + "For a change of an existing nonempty plan or a FAILED step, AMEND with a fresh stepId, preserve all retained goals and add current source-linked goals. "
                + "supersedesStepIds names only replaced unexecuted steps. A replacement must NOT depend on itself, a superseded step or an unconfirmed/failed result. "
                + "Read actual planSummary.steps[].dependsOn/goalIds/status/receiptId; preserve required predecessors instead of guessing dependencies. "
                + "CONTINUE only an actual READY/WAITING_INPUT/WAITING_CONFIRMATION step for the existing request, such as answering its pending question. "
                + "Within a nonempty plan, a new dashboard modification AMENDs with its requiredIntent; CONTINUE is not an edit of an old creation step. "
                + "For CONTINUE steps/proposedGoals/supersedes arrays are empty and targetStepId names that step. "
                + "fact_ref must name an exact facts[].factId. A resourceChoices ref is not a fact. step_output requires an explicitly declared dependency and output. "
                + "The server supplies current-message provenance for new goals. Never delete a retained goal implicitly or claim execution/confirmation.\n"
                + "Replacement example (illustrative IDs, use actual request IDs): old dashboard step d0 is FAILED, no predecessors, goal g0 remains pending. "
                + "For the new user change AMEND with supersedesStepIds=[d0], new step d1, dependsOn=[], goalIds=[g0,g1]; "
                + "g1 is a proposed goal sourced to the current message. Do not set dependsOn=[d0]. "
                + "If d0 depended on a SUCCEEDED device step dev0, the replacement may retain dev0 and its actual declared deviceId output; never create that device again.\n"
                + "Complete illustrative wire examples (replace local ids and references with the actual request snapshot):\n"
                + "Empty plan; selected device ref=resource1; user asks for a useful dashboard.\n"
                + "{\"version\":\"assistant-v2\",\"kind\":\"planner.response\",\"decision\":\"PLAN\",\"explanation\":\"Prepare a new dashboard for the selected device.\","
                + "\"targetStepId\":null,\"supersedesStepIds\":[],\"question\":null,\"steps\":[{\"stepId\":\"d1\",\"intentId\":\"CREATE_DASHBOARD\",\"dependsOn\":[],"
                + "\"inputs\":[{\"field\":\"deviceId\",\"binding\":{\"kind\":\"resource_refs\",\"refs\":[\"resource1\"]}}],\"goalIds\":[\"g1\"]}],"
                + "\"proposedGoals\":[{\"goalId\":\"g1\",\"text\":\"Create the requested dashboard\"}],\"supersedesGoalIds\":[]}\n"
                + "Selected DASHBOARD review named Overview, requiredIntent=REFINE_DASHBOARD_REVIEW; d1 is WAITING_CONFIRMATION with pending g1. User changes its name to Workshop.\n"
                + "{\"version\":\"assistant-v2\",\"kind\":\"planner.response\",\"decision\":\"AMEND\",\"explanation\":\"Refine the selected dashboard review using the supplied name.\","
                + "\"targetStepId\":null,\"supersedesStepIds\":[\"d1\"],\"question\":null,\"steps\":[{\"stepId\":\"d2\",\"intentId\":\"REFINE_DASHBOARD_REVIEW\",\"dependsOn\":[],"
                + "\"inputs\":[],\"goalIds\":[\"g1\",\"g2\"]}],\"proposedGoals\":[{\"goalId\":\"g2\",\"text\":\"Apply the requested dashboard name change\"}],\"supersedesGoalIds\":[]}\n";
    }
}
