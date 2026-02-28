package org.example.apiagtewaybe.authz;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authorization filter that enforces role requirements defined in
 * downstream services' OpenAPI specs.
 *
 * <p>
 * Runs after JWT authentication. For each request:
 * <ol>
 * <li>Looks up required roles from the cached OpenAPI spec</li>
 * <li>Compares against the user's roles from the JWT</li>
 * <li>Returns 403 if the user lacks any required role</li>
 * <li>Passes through if roles match or no spec rule exists</li>
 * </ol>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 5)
public class SwaggerAuthzFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SwaggerAuthzFilter.class);

    private final OpenApiSpecRegistry specRegistry;

    public SwaggerAuthzFilter(OpenApiSpecRegistry specRegistry) {
        this.specRegistry = specRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String method = request.getMethod();
        String path = request.getRequestURI();

        // Look up required roles from the OpenAPI spec
        List<String> requiredRoles = specRegistry.getRequiredRoles(method, path);

        if (requiredRoles.isEmpty()) {
            // No role restriction defined for this endpoint — pass through
            filterChain.doFilter(request, response);
            return;
        }

        // Get the authenticated user's roles
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            // Should not happen (JWT filter runs first), but just in case
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Not authenticated");
            return;
        }

        Set<String> userRoles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.replace("ROLE_", "")) // Strip Spring Security's ROLE_ prefix
                .collect(Collectors.toSet());

        // Check if the user has at least one of the required roles
        boolean hasAccess = requiredRoles.stream().anyMatch(userRoles::contains);

        if (!hasAccess) {
            log.warn("Access denied: user roles {} do not match required roles {} for {} {}",
                    userRoles, requiredRoles, method, path);
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Insufficient permissions. Required roles: " + requiredRoles);
            return;
        }

        log.debug("Access granted: {} {} — user roles: {}, required: {}",
                method, path, userRoles, requiredRoles);
        filterChain.doFilter(request, response);
    }
}
