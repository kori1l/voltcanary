package com.voltcanary.aiagent.llm.adapter;

public class IncompleteModelResponseException extends IllegalArgumentException {

    public IncompleteModelResponseException() {
        super("MODEL_OUTPUT_LIMIT");
    }
}
