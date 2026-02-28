package org.example.apigatewaybe.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Binds the downstream service OpenAPI spec URLs from application.yaml.
 *
 * <p>
 * Example config:
 * 
 * <pre>
 * gateway:
 *   authz:
 *     specs:
 *       metadata-service: http://localhost:8081/v3/api-docs
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "gateway.authz")
public class AuthzConfigProperties {

    /**
     * Map of service-name → OpenAPI spec URL.
     */
    private Map<String, String> specs = new HashMap<>();

    public Map<String, String> getSpecs() {
        return specs;
    }

    public void setSpecs(Map<String, String> specs) {
        this.specs = specs;
    }
}
