package com.aegispay.analytics.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the real {@code application.yml} to {@link AnalyticsProperties}.
 *
 * <p><strong>Why this test exists.</strong> A misspelled configuration key neither fails to compile nor
 * fails to bind — Spring leaves the field at its default and says nothing. Here the symptom would be
 * particularly quiet: if {@code stale-after} failed to bind, the API would keep answering, keep returning
 * numbers, and keep reporting freshness. It would simply use a different tolerance than the file claims, and
 * the only way to notice would be a dashboard that called a healthy stream stale, or worse, a dead one
 * healthy.
 *
 * <p>Normally a context starting would catch a structural mistake. It cannot here: the integration tests that
 * boot a context need Docker, which is unavailable in this environment, so without this test the whole
 * {@code aegis.analytics.*} tree ships unverified. This runs in milliseconds against no infrastructure.
 *
 * <p>It reads the shipped file rather than an inline fixture. A fixture would prove that binding works in
 * general, which was never in doubt; the question is whether <em>this</em> file's keys match <em>these</em>
 * field names.
 */
class AnalyticsPropertiesBindingTest {

    private static final String PREFIX = "aegis.analytics";

    private final AnalyticsProperties properties = bindShippedConfiguration();

    // ------------------------------------------------------------------------
    // binding
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("keys bind")
    class Binding {

        @Test
        @DisplayName("binds every analytics key from the shipped configuration")
        void bindsEveryKey() {
            assertThat(properties.getStaleAfter())
                    .as("aegis.analytics.stale-after")
                    .isEqualTo(Duration.ofMinutes(2));
            assertThat(properties.getReportingCurrency())
                    .as("aegis.analytics.reporting-currency")
                    .isEqualTo("INR");
            assertThat(properties.getMaximumPageSize())
                    .as("aegis.analytics.maximum-page-size")
                    .isEqualTo(100);
        }

        /**
         * The defaults in the class must match the values in the file. When they diverge, a deployment that
         * omits the key behaves differently from one that ships it — and the difference is invisible because
         * both look configured.
         */
        @Test
        @DisplayName("the coded defaults agree with the shipped values")
        void defaultsAgreeWithFile() {
            AnalyticsProperties unbound = new AnalyticsProperties();

            assertThat(unbound.getStaleAfter()).isEqualTo(properties.getStaleAfter());
            assertThat(unbound.getReportingCurrency()).isEqualTo(properties.getReportingCurrency());
            assertThat(unbound.getMaximumPageSize()).isEqualTo(properties.getMaximumPageSize());
        }
    }

    // ------------------------------------------------------------------------
    // the values themselves
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("the values are operationally sane")
    class Values {

        /**
         * Staleness has to be meaningfully above the platform's healthy lag or the banner cries wolf, and
         * meaningfully below a shift length or a dead consumer goes unreported. Healthy lag here is
         * sub-second: the outbox drains every 200ms and consumers commit per record.
         */
        @Test
        @DisplayName("the staleness tolerance absorbs a restart without hiding a dead consumer")
        void staleAfterIsUseful() {
            assertThat(properties.getStaleAfter())
                    .as("""
                            below a few seconds this flags every rolling restart, and an operator who \
                            learns to ignore the banner has lost the one signal that the numbers are old""")
                    .isGreaterThanOrEqualTo(Duration.ofSeconds(30));

            assertThat(properties.getStaleAfter())
                    .as("""
                            above a few minutes a dead consumer serves confident, wrong numbers for long \
                            enough that somebody acts on them""")
                    .isLessThanOrEqualTo(Duration.ofMinutes(15));
        }

        /**
         * The page ceiling exists because this list is platform-wide. A customer-scoped endpoint is naturally
         * bounded by how many payments one person made; this one is bounded only by what a caller asks for.
         */
        @Test
        @DisplayName("the page ceiling bounds a platform-wide list without making it unusable")
        void pageSizeIsBounded() {
            assertThat(properties.getMaximumPageSize())
                    .as("a page of one turns a review of yesterday's failures into hundreds of requests")
                    .isGreaterThanOrEqualTo(20);
            assertThat(properties.getMaximumPageSize())
                    .as("""
                            this is every payment on the platform; an unbounded page is a way to ask one \
                            service to serialise the whole table""")
                    .isLessThanOrEqualTo(500);
        }

        /**
         * The currency label is only a label. This service sums minor units and does not convert, because it
         * has no rate source — and inventing one would produce authoritative-looking totals derived from a
         * made-up number. A blank or malformed code would put an empty currency on every money figure.
         */
        @Test
        @DisplayName("the reporting currency is a well-formed ISO code")
        void reportingCurrencyIsAnIsoCode() {
            assertThat(properties.getReportingCurrency())
                    .as("this label appears on every aggregate total the dashboard renders")
                    .matches("[A-Z]{3}");
        }

        /**
         * Minor units are only comparable within one currency, so the label has to name a currency the
         * platform actually issues amounts in.
         */
        @Test
        @DisplayName("the reporting currency is one the platform uses")
        void reportingCurrencyIsKnown() {
            assertThat(properties.getReportingCurrency()).isEqualTo("INR");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static AnalyticsProperties bindShippedConfiguration() {
        AnalyticsProperties bound = new AnalyticsProperties();
        Binder.get(shippedEnvironment()).bind(PREFIX, Bindable.ofInstance(bound));
        return bound;
    }

    private static StandardEnvironment shippedEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
            sources.forEach(source -> environment.getPropertySources().addFirst(source));
        } catch (IOException ex) {
            throw new AssertionError("could not read analytics-service's application.yml", ex);
        }
        return environment;
    }
}
