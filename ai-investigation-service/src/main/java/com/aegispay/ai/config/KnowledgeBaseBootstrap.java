package com.aegispay.ai.config;

import com.aegispay.ai.rag.KnowledgeBaseSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Seeds the policy corpus once the application context is up.
 *
 * <h2>Why an {@code ApplicationRunner} rather than {@code @PostConstruct}</h2>
 *
 * <p>Seeding needs Flyway to have run, the datasource to be live and the transaction infrastructure to be
 * ready. A {@code @PostConstruct} on a bean can run before other beans are initialised, and the failure mode
 * would be an obscure ordering error rather than a clear message. A runner executes after the whole context
 * is refreshed, which is exactly the guarantee needed.
 *
 * <h2>Why a failure stops the service</h2>
 *
 * <p>An {@code ApplicationRunner} that throws prevents startup, and that is the intent. A service running
 * with an empty or partially-embedded corpus would answer every investigation ungrounded and report no
 * error: the reports would still be generated, still be read, and still look exactly like the grounded ones.
 * A pod that refuses to start is loud; one that quietly produces unsupported narratives is not.
 *
 * <p>This is the opposite trade from the generator, where a missing API key logs a warning and falls back to
 * the offline composer. The difference is that a fallback generator still produces a truthful report, while
 * there is no fallback for having no policy to cite.
 */
@Component
@ConditionalOnProperty(prefix = "aegis.ai.knowledge-base", name = "seed-on-startup",
        matchIfMissing = true)
public class KnowledgeBaseBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseBootstrap.class);

    private final KnowledgeBaseSeeder seeder;

    public KnowledgeBaseBootstrap(KnowledgeBaseSeeder seeder) {
        this.seeder = seeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seeder.seed();
        } catch (RuntimeException ex) {
            log.error("""
                    Could not seed the fraud-policy knowledge base, so this service will not start. \
                    Without a corpus every investigation would report no grounding while still \
                    producing a readable narrative, which is worse than not running at all.""", ex);
            throw ex;
        }
    }
}
