package com.aegispay.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CitationProvenanceMigrationTest {

    @Test
    @DisplayName("V6 removes only the citation trigger for backfill and restores it afterwards")
    void appendOnlyTriggerSurroundsHistoricalBackfill() throws IOException {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V6__distinguish_retrieved_and_cited_policy.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        int drop = sql.indexOf("DROP TRIGGER trg_investigation_citations_append_only");
        int update = sql.indexOf("UPDATE investigation_citations");
        int create = sql.indexOf("CREATE TRIGGER trg_investigation_citations_append_only");

        assertThat(drop).isGreaterThanOrEqualTo(0);
        assertThat(update).isGreaterThan(drop);
        assertThat(create).isGreaterThan(update);
        assertThat(sql).doesNotContain("DROP TRIGGER trg_investigations_append_only")
                .doesNotContain("DROP TRIGGER trg_investigation_reviews_append_only");
    }
}
