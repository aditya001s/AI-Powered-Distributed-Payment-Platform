package com.aegispay.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the aggregate Swagger routes to the documents downstream services actually expose. */
class GatewayOpenApiConfigurationTest {

    private static final List<String> COMPONENTS = List.of(
            "auth", "wallet", "payment", "ledger", "orchestrator", "fraud",
            "investigation", "notification", "analytics");

    @Test
    @DisplayName("every advertised service document has a dedicated rewrite to /v3/api-docs")
    void aggregateDocumentsAreRoutable() {
        String yaml = readApplicationYaml();

        assertThat(yaml)
                .as("each RewritePath shortcut must be one YAML list item; an unquoted comma "
                        + "creates a second item and leaves the replacement null")
                .doesNotContain("filters: [RewritePath=");
        assertThat(yaml.split(java.util.regex.Pattern.quote("filters: [\"RewritePath="), -1).length - 1)
                .isEqualTo(COMPONENTS.size());

        for (String component : COMPONENTS) {
            String publicPath = "/docs/" + component + "/v3/api-docs";
            assertThat(yaml)
                    .as("Swagger must advertise %s", component)
                    .contains("url: " + publicPath)
                    .as("the gateway must route and rewrite %s", component)
                    .contains("Path=" + publicPath)
                    .contains("RewritePath=" + publicPath + ", /v3/api-docs");
        }
    }

    @Test
    @DisplayName("legacy prefixed document URLs are not advertised")
    void doesNotAdvertiseBusinessPathsAsDocumentRoutes() {
        assertThat(readApplicationYaml())
                .doesNotContain("url: /api/auth/v3/api-docs")
                .doesNotContain("url: /api/admin/v3/api-docs");
    }

    private static String readApplicationYaml() {
        try (InputStream input = new ClassPathResource("application.yml").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new AssertionError("could not read api-gateway application.yml", ex);
        }
    }
}
