package com.voltcanary.aiagent.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class ModelPlanningFailedException extends ResponseStatusException {
    private final boolean outputLimit;

    public ModelPlanningFailedException(boolean outputLimit) {
        super(HttpStatus.BAD_GATEWAY, outputLimit
                ? "Модель исчерпала настроенный объём ответа. Увеличьте Maximum output tokens в настройках модели и повторите запрос. Уточнение сохранено."
                : "Модель не подготовила проверяемый ответ. Уточнение сохранено; повторите запрос.");
        this.outputLimit = outputLimit;
    }

    public String code() {
        return outputLimit ? "MODEL_OUTPUT_LIMIT" : "PROVIDER_FAILED";
    }
}
