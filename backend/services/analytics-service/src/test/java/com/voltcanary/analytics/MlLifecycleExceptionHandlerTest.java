package com.voltcanary.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.voltcanary.analytics.controller.MlLifecycleExceptionHandler;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class MlLifecycleExceptionHandlerTest {

    @Test
    void commitTimeOptimisticConflictsReturn409WithoutInternalDetails() throws Exception {
        var http = MockMvcBuilders.standaloneSetup(new ConflictEndpoints())
            .setControllerAdvice(new MlLifecycleExceptionHandler())
            .build();
        for (String path : new String[] {
            "/spring-conflict",
            "/jpa-conflict",
            "/commit-conflict",
        }) {
            http.perform(get(path))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"))
                .andExpect(
                    jsonPath("$.message").value(
                        "ML data changed during this operation; reload before retrying"
                    )
                );
        }
    }

    @RestController
    static class ConflictEndpoints {

        @GetMapping("/spring-conflict")
        void springConflict() {
            throw new OptimisticLockingFailureException("Internal persistence details");
        }

        @GetMapping("/jpa-conflict")
        void jpaConflict() {
            throw new OptimisticLockException("Internal persistence details");
        }

        @GetMapping("/commit-conflict")
        void commitConflict() {
            throw new org.springframework.transaction.TransactionSystemException(
                "Commit failed",
                new jakarta.persistence.RollbackException(
                    "Rolled back",
                    new OptimisticLockException("Internal persistence details")
                )
            );
        }
    }
}
