package com.voltcanary.analytics.controller;

import com.voltcanary.analytics.dto.MlLifecycleConflictResponse;
import com.voltcanary.analytics.service.MlLifecycleConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class MlLifecycleExceptionHandler {
    @ExceptionHandler(MlLifecycleConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public MlLifecycleConflictResponse lifecycleConflict(MlLifecycleConflictException exception) {
        return exception.response();
    }
}
