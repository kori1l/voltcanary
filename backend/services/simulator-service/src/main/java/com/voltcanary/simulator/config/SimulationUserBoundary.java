package com.voltcanary.simulator.config;

import com.voltcanary.simulator.service.SimulationUserAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class SimulationUserBoundary implements WebMvcConfigurer {

    private final SimulationUserAccess access;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry
            .addInterceptor(
                new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(
                        HttpServletRequest request,
                        HttpServletResponse response,
                        Object handler
                    ) {
                        String path = request
                            .getRequestURI()
                            .substring(request.getContextPath().length());
                        String[] parts = path.split("/");
                        String authorization = request.getHeader("Authorization");
                        if (
                            parts.length >= 4 &&
                            "devices".equals(parts[2]) &&
                            !path.endsWith("/config/validate") &&
                            !path.endsWith("/config/reset-template")
                        ) {
                            access.requireDevice(authorization, UUID.fromString(parts[3]));
                        } else if (parts.length == 4 && "runs".equals(parts[2])) {
                            access.requireRun(authorization, UUID.fromString(parts[3]));
                        }
                        return true;
                    }
                }
            )
            .addPathPatterns("/simulation/**");
    }
}
