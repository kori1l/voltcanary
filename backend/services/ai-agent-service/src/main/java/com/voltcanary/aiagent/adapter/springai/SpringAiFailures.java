package com.voltcanary.aiagent.adapter.springai;

/** Classify public status only; SDK messages/bodies can contain sensitive provider data. */
public final class SpringAiFailures {

    private SpringAiFailures() {}

    public static Integer status(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (
                cause instanceof com.openai.errors.OpenAIServiceException openai
            ) return openai.statusCode();
            if (
                cause instanceof com.anthropic.errors.AnthropicServiceException anthropic
            ) return anthropic.statusCode();
            if (cause instanceof com.google.genai.errors.ApiException google) return google.code();
            if (
                cause instanceof org.springframework.web.client.RestClientResponseException http
            ) return http.getStatusCode().value();
            if (cause == cause.getCause()) break;
        }
        return null;
    }
}
