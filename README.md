# API Gateway

A centralized API Gateway built with **Spring Boot 4.0.3** and **Spring Cloud Gateway (WebMVC)**. Handles authentication, authorization, and request routing for downstream microservices.

## Architecture

```
                                        ┌──────────────────┐
                                        │   Keycloak IDP   │
                                        │ (issues JWT)     │
                                        └────────┬─────────┘
                                                 │ JWKS
┌──────────┐     ┌───────────────────────────────┼───────────────────┐
│  Client   │────▶│           API Gateway (:8087)  ▼                  │
│           │     │                                                    │
│ Bearer    │     │  1. JWT Authentication (Keycloak)                 │
│ Token     │     │  2. Swagger-Driven Authorization                  │
│           │     │     → Reads x-required-roles from service specs   │
│           │     │  3. Route & Forward (with user identity headers)  │
└──────────┘     └───────────┬──────────────────────┬────────────────┘
                             │                      │
                             ▼                      ▼
                   ┌──────────────────┐   ┌──────────────────┐
                   │ Metadata Service │   │ Other Services   │
                   │     (:8081)      │   │                  │
                   └──────────────────┘   └──────────────────┘
```

## Features

- **JWT Authentication** — Validates Bearer tokens against Keycloak via JWKS
- **Swagger-Driven Authorization** — Reads `x-required-roles` from each service's OpenAPI spec and enforces role-based access at the gateway level
- **User Identity Forwarding** — Passes `X-User-Id`, `X-User-Email`, `X-User-Roles` headers to downstream services
- **Automatic Spec Refresh** — Periodically re-fetches downstream OpenAPI specs (configurable interval)

## Tech Stack

| Component | Version |
|-----------|---------|
| Spring Boot | 4.0.3 |
| Spring Cloud (Oakwood) | 2025.1.0 |
| Spring Cloud Gateway WebMVC | — |
| Spring Security + OAuth2 Resource Server | — |
| Java | 17 |

## Project Structure

```
src/main/java/org/example/apigatewaybe/
├── ApigatewayBeApplication.java          # Entry point, @EnableScheduling
├── config/
│   ├── SecurityConfig.java               # JWT validation, public paths, stateless sessions
│   └── KeycloakRoleConverter.java        # Maps Keycloak realm_access.roles → Spring authorities
├── authz/
│   ├── AuthzConfigProperties.java        # Binds gateway.authz.specs from YAML
│   ├── OpenApiSpecRegistry.java          # Fetches/caches OpenAPI specs, extracts x-required-roles
│   └── SwaggerAuthzFilter.java           # Enforces role-based access per request
└── filter/
    └── AddUserHeadersFilter.java         # Forwards user identity to downstream services
```

## Configuration

### `application.yaml`

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8083/realms/main}
  cloud:
    gateway:
      server:
        webmvc:
          routes:
            - id: MetaData-SERVICE
              uri: http://localhost:8081
              predicates:
                - Path=/api/metadata/**

server:
  port: 8087

# Swagger-driven authorization
gateway:
  authz:
    specs:
      metadata-service: http://localhost:8081/v3/api-docs
    refresh-interval-ms: 300000  # 5 minutes
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8083/realms/main` | Keycloak realm issuer URL |

## Getting Started

### Prerequisites

- Java 17+
- Maven
- Running Keycloak instance
- Downstream services (e.g., Metadata Service on port 8081)

### Run

```bash
# Build
mvn clean compile

# Start the gateway
mvn spring-boot:run
```

The gateway starts on **port 8087**.

### Add a New Downstream Service

1. **In the downstream service** — add SpringDoc and annotate endpoints:
   ```java
   @GetMapping("/{id}")
   @Operation(
       summary = "Get resource",
       extensions = @Extension(properties =
           @ExtensionProperty(name = "x-required-roles", value = "[\"USER\",\"ADMIN\"]"))
   )
   public ResponseEntity<Resource> getById(@PathVariable Long id) { ... }
   ```

2. **In the gateway** — add a route and register the spec URL:
   ```yaml
   spring.cloud.gateway.server.webmvc.routes:
     - id: NEW-SERVICE
       uri: http://localhost:PORT
       predicates:
         - Path=/api/new-service/**

   gateway.authz.specs:
     new-service: http://localhost:PORT/v3/api-docs
   ```

## Auth Flow

| Step | Component | Action |
|------|-----------|--------|
| 1 | Client | Authenticates with Keycloak, gets JWT |
| 2 | Gateway | Validates JWT signature via Keycloak JWKS |
| 3 | Gateway | Extracts roles from `realm_access.roles` |
| 4 | Gateway | Matches request to OpenAPI spec, checks `x-required-roles` |
| 5 | Gateway | Forwards request with `X-User-Id`, `X-User-Email`, `X-User-Roles` headers |
| 6 | Service | Processes request (trusts gateway-forwarded headers) |

### HTTP Responses

| Scenario | Status |
|----------|--------|
| No token | `401 Unauthorized` |
| Invalid/expired token | `401 Unauthorized` |
| Valid token, insufficient role | `403 Forbidden` |
| Valid token, authorized | `200` (proxied from downstream) |
| `/actuator/health` (no token) | `200` (public) |

## Testing

```bash
# Get a token from Keycloak
curl -X POST http://localhost:8083/realms/main/protocol/openid-connect/token \
  -d "grant_type=client_credentials" \
  -d "client_id=YOUR_CLIENT" \
  -d "client_secret=YOUR_SECRET"

# Authenticated request
curl -H "Authorization: Bearer <token>" http://localhost:8087/api/metadata/1

# Public endpoint (no token needed)
curl http://localhost:8087/actuator/health
```
