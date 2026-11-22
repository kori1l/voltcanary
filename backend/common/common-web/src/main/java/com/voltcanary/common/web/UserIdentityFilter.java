package com.voltcanary.common.web;

import com.voltcanary.common.security.BearerUserIdentity;
import com.voltcanary.common.security.JwtAccessTokenClaims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adapts verified JWT claims to legacy controller inputs; client identity hints never survive. */
public final class UserIdentityFilter extends OncePerRequestFilter {

    private final BearerUserIdentity identity;

    public UserIdentityFilter(BearerUserIdentity identity) {
        this.identity = identity;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        // Explicit opt-in only on user-serving applications. New routes require JWT by default.
        return (
            under(path, "/internal") ||
            under(path, "/actuator") ||
            under(path, "/v3/api-docs") ||
            under(path, "/swagger-ui") ||
            path.equals("/swagger-ui.html") ||
            path.equals("/error")
        );
    }

    private boolean under(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        JwtAccessTokenClaims claims;
        try {
            claims = identity.verify(request.getHeader("Authorization"));
        } catch (IllegalArgumentException exception) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response
                .getWriter()
                .write(
                    "{\"code\":\"UNAUTHORIZED\",\"message\":\"Valid bearer access token required\"}"
                );
            return;
        }
        chain.doFilter(new VerifiedRequest(request, claims), response);
    }

    private static final class VerifiedRequest extends HttpServletRequestWrapper {

        private final Map<String, String> claims;

        private VerifiedRequest(HttpServletRequest request, JwtAccessTokenClaims claims) {
            super(request);
            this.claims = Map.of(
                "x-user-id",
                claims.subject(),
                "x-user-email",
                claims.email(),
                "x-user-role",
                claims.role()
            );
        }

        private boolean untrusted(String name) {
            String key = name.toLowerCase(Locale.ROOT);
            return (
                key.startsWith("x-user-") ||
                key.startsWith("x-account-") ||
                key.startsWith("x-workspace-") ||
                key.startsWith("x-capabilit") ||
                key.equals("x-internal-service-token")
            );
        }

        @Override
        public String getHeader(String name) {
            String value = claims.get(name.toLowerCase(Locale.ROOT));
            return value != null ? value : untrusted(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String value = getHeader(name);
            if (untrusted(name)) {
                return Collections.enumeration(value == null ? Set.of() : Set.of(value));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            Collections.list(super.getHeaderNames())
                .stream()
                .filter(name -> !untrusted(name))
                .forEach(names::add);
            names.addAll(claims.keySet());
            return Collections.enumeration(names);
        }
    }
}
