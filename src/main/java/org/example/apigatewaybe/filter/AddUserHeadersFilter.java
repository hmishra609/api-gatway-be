package org.example.apigatewaybe.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Extracts user identity from the validated JWT and adds it as headers
 * forwarded to downstream microservices.
 *
 * <p>
 * Headers added:
 * <ul>
 * <li>{@code X-User-Id} — from the {@code sub} claim</li>
 * <li>{@code X-User-Email} — from the {@code email} claim</li>
 * <li>{@code X-User-Roles} — comma-separated roles from
 * {@code realm_access.roles}</li>
 * </ul>
 *
 * <p>
 * Downstream services can trust these headers since the gateway
 * has already validated the JWT.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class AddUserHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();

            // Extract user info from JWT claims
            String userId = jwt.getSubject();
            String email = jwt.getClaimAsString("email");
            String roles = jwtAuth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.joining(","));

            // Wrap the request to add custom headers
            HttpServletRequest wrappedRequest = new UserHeadersRequestWrapper(
                    request, userId, email, roles);

            filterChain.doFilter(wrappedRequest, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Request wrapper that adds user identity headers to the forwarded request.
     */
    private static class UserHeadersRequestWrapper extends HttpServletRequestWrapper {

        private final Map<String, String> customHeaders = new HashMap<>();

        public UserHeadersRequestWrapper(HttpServletRequest request,
                String userId,
                String email,
                String roles) {
            super(request);
            if (userId != null)
                customHeaders.put("X-User-Id", userId);
            if (email != null)
                customHeaders.put("X-User-Email", email);
            if (roles != null && !roles.isEmpty())
                customHeaders.put("X-User-Roles", roles);
        }

        @Override
        public String getHeader(String name) {
            String customValue = customHeaders.get(name);
            return customValue != null ? customValue : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String customValue = customHeaders.get(name);
            if (customValue != null) {
                return Collections.enumeration(List.of(customValue));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>(customHeaders.keySet());
            Enumeration<String> originalNames = super.getHeaderNames();
            while (originalNames.hasMoreElements()) {
                names.add(originalNames.nextElement());
            }
            return Collections.enumeration(names);
        }
    }
}
