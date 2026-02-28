package org.example.apigatewaybe.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Fetches and caches OpenAPI specs from downstream services on startup.
 * Extracts {@code x-required-roles} from each operation and builds a
 * lookup map: (HTTP method + path pattern) → required roles.
 *
 * <p>
 * Path templates like {@code /api/metadata/{id}} are converted to
 * regex patterns for runtime matching.
 */
@Component
public class OpenApiSpecRegistry {

    private static final Logger log = LoggerFactory.getLogger(OpenApiSpecRegistry.class);

    private final AuthzConfigProperties config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Key: "METHOD:/path/pattern" (e.g. "GET:/api/metadata/{id}")
     * Value: list of required role names (e.g. ["USER", "ADMIN"])
     */
    private final Map<String, List<String>> roleRequirements = new ConcurrentHashMap<>();

    /**
     * Compiled regex patterns for path matching.
     * Key: regex pattern string, Value: compiled Pattern
     */
    private final Map<String, Pattern> pathPatterns = new ConcurrentHashMap<>();

    /**
     * Maps regex pattern → original spec key for lookup.
     */
    private final Map<String, String> patternToKey = new ConcurrentHashMap<>();

    public OpenApiSpecRegistry(AuthzConfigProperties config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        refreshSpecs();
    }

    @Scheduled(fixedDelayString = "${gateway.authz.refresh-interval-ms:300000}")
    public void refreshSpecs() {
        for (Map.Entry<String, String> entry : config.getSpecs().entrySet()) {
            String serviceName = entry.getKey();
            String specUrl = entry.getValue();
            try {
                log.info("Fetching OpenAPI spec from {} ({})", serviceName, specUrl);
                String json = restTemplate.getForObject(specUrl, String.class);
                parseSpec(json);
                log.info("Successfully loaded spec from {} — {} role rules registered",
                        serviceName, roleRequirements.size());
            } catch (Exception e) {
                log.warn("Failed to fetch OpenAPI spec from {} ({}): {}",
                        serviceName, specUrl, e.getMessage());
            }
        }
    }

    private void parseSpec(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode paths = root.get("paths");
            if (paths == null)
                return;

            paths.fieldNames().forEachRemaining(path -> {
                JsonNode pathItem = paths.get(path);
                pathItem.fieldNames().forEachRemaining(method -> {
                    if (isHttpMethod(method)) {
                        JsonNode operation = pathItem.get(method);
                        List<String> roles = extractRequiredRoles(operation);
                        if (!roles.isEmpty()) {
                            String key = method.toUpperCase() + ":" + path;
                            roleRequirements.put(key, roles);

                            // Build regex for path template matching
                            String regex = pathToRegex(path);
                            String regexKey = method.toUpperCase() + ":" + regex;
                            pathPatterns.put(regexKey, Pattern.compile(regex));
                            patternToKey.put(regexKey, key);

                            log.debug("Registered: {} → roles: {}", key, roles);
                        }
                    }
                });
            });
        } catch (Exception e) {
            log.error("Failed to parse OpenAPI spec: {}", e.getMessage());
        }
    }

    private List<String> extractRequiredRoles(JsonNode operation) {
        // Look for x-required-roles in the operation extensions
        JsonNode xRoles = operation.get("x-required-roles");
        if (xRoles != null && xRoles.isArray()) {
            List<String> roles = new ArrayList<>();
            xRoles.forEach(node -> roles.add(node.asText()));
            return roles;
        }

        // Also check inside the extension wrapper format from SpringDoc
        // SpringDoc may nest it as: extensions[].properties[].name/value
        return Collections.emptyList();
    }

    /**
     * Converts an OpenAPI path template to a regex.
     * e.g. "/api/metadata/{id}" → "/api/metadata/[^/]+"
     */
    private String pathToRegex(String path) {
        return "^" + path.replaceAll("\\{[^}]+}", "[^/]+") + "$";
    }

    private boolean isHttpMethod(String method) {
        return Set.of("get", "post", "put", "delete", "patch", "head", "options")
                .contains(method.toLowerCase());
    }

    /**
     * Looks up the required roles for a given HTTP method and request path.
     *
     * @return list of required roles, or empty list if no restriction found
     */
    public List<String> getRequiredRoles(String method, String path) {
        // 1. Try exact match first
        String exactKey = method.toUpperCase() + ":" + path;
        List<String> roles = roleRequirements.get(exactKey);
        if (roles != null)
            return roles;

        // 2. Try regex pattern match
        String methodUpper = method.toUpperCase();
        for (Map.Entry<String, Pattern> entry : pathPatterns.entrySet()) {
            if (entry.getKey().startsWith(methodUpper + ":")) {
                if (entry.getValue().matcher(path).matches()) {
                    String originalKey = patternToKey.get(entry.getKey());
                    return roleRequirements.getOrDefault(originalKey, Collections.emptyList());
                }
            }
        }

        return Collections.emptyList();
    }

    /**
     * @return true if any specs have been loaded
     */
    public boolean hasSpecs() {
        return !roleRequirements.isEmpty();
    }
}
