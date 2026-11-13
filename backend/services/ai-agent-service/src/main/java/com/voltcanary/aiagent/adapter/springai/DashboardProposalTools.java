package com.voltcanary.aiagent.adapter.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.voltcanary.aiagent.service.DashboardSkillContract;

/** Domain-specific native tools submit proposals to the existing durable review boundary. */
public final class DashboardProposalTools {
    public static final String CREATE = "prepare_dashboard_creation";
    public static final String REFINE = "prepare_dashboard_refinement";
    public static final String UPDATE = "prepare_dashboard_update";

    private DashboardProposalTools() { }

    public static String select(JsonNode request, boolean assessing) {
        if (assessing || !"step.request".equals(request.path("kind").asText())) return ReviewedProposalTool.NAME;
        return switch (request.path("intentId").asText()) {
            case DashboardSkillContract.CREATE -> CREATE;
            case DashboardSkillContract.REFINE -> REFINE;
            case DashboardSkillContract.UPDATE -> UPDATE;
            default -> ReviewedProposalTool.NAME;
        };
    }

    public static String description(String name) {
        return switch (name) {
            case CREATE -> "Prepare a new dashboard review using authorized data and semantic widget operations. "
                    + "Use local references for new widgets. Return PREPARED, QUESTION or NEED_CONTEXT. Does not save a dashboard.";
            case REFINE -> "Prepare changes to the selected unexecuted dashboard review. Use exact existing widget keys "
                    + "for place_widgets, resize_widgets, set_presentation, set_data and other registered operations. "
                    + "Return PREPARED, QUESTION or NEED_CONTEXT. Does not execute or save changes.";
            case UPDATE -> "Prepare an update to the same existing dashboard draft using its authorized current revision. "
                    + "Use exact widget keys for movement, size, settings and other registered operations. "
                    + "Return PREPARED, QUESTION or NEED_CONTEXT. Requires server validation and new confirmation before saving.";
            case ReviewedProposalTool.NAME -> "Submit one proposal for server validation and review. Does not execute any action.";
            default -> throw new IllegalArgumentException("UNREGISTERED_PROPOSAL_TOOL");
        };
    }
}
