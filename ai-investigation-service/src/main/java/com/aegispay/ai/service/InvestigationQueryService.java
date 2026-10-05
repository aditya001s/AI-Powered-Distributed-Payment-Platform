package com.aegispay.ai.service;

import com.aegispay.ai.api.AiDtos.AgreementSummary;
import com.aegispay.ai.api.AiDtos.CitationView;
import com.aegispay.ai.api.AiDtos.CitedDocument;
import com.aegispay.ai.api.AiDtos.EngineInfoView;
import com.aegispay.ai.api.AiDtos.GeneratorSummary;
import com.aegispay.ai.api.AiDtos.InvestigationDetailView;
import com.aegispay.ai.api.AiDtos.InvestigationView;
import com.aegispay.ai.api.AiDtos.PolicyDetailView;
import com.aegispay.ai.api.AiDtos.PolicyView;
import com.aegispay.ai.api.AiDtos.RecommendationCount;
import com.aegispay.ai.api.AiDtos.ReviewView;
import com.aegispay.ai.api.AiDtos.StatsView;
import com.aegispay.ai.config.AiProperties;
import com.aegispay.ai.domain.Investigation;
import com.aegispay.ai.domain.PolicyDocument;
import com.aegispay.ai.domain.Recommendation;
import com.aegispay.ai.generation.ReportGenerator;
import com.aegispay.ai.rag.PolicyRetriever;
import com.aegispay.ai.repository.InvestigationCitationRepository;
import com.aegispay.ai.repository.InvestigationRepository;
import com.aegispay.ai.repository.InvestigationReviewRepository;
import com.aegispay.ai.repository.PolicyChunkRepository;
import com.aegispay.ai.repository.PolicyDocumentRepository;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Every read of investigation state and of the knowledge base.
 *
 * <p><strong>Read-only, entirely.</strong> Separated from {@link InvestigationMutationService} so the
 * boundary is visible in the type rather than only in an annotation: nothing reachable from here can
 * change a report, which is the property a reader of this service most needs to be able to assume given
 * that reports are append-only.
 *
 * <p>Every method is {@code readOnly}, which is not decoration. It puts the JDBC connection in read-only
 * mode, so a mapping mistake that tried to flush a dirty investigation fails at the database rather than
 * quietly attempting an UPDATE the trigger would then refuse — and the trigger's error message is far
 * less useful than a read-only violation at the point of the mistake.
 */
@Service
@Transactional(readOnly = true)
public class InvestigationQueryService {

    /**
     * The window every "recent" statistic uses.
     *
     * <p>One constant rather than a parameter per call, because the numbers are compared against each
     * other on a dashboard: investigations over a day next to hallucinations over an hour would make the
     * hallucination rate look far better than it is.
     */
    private static final Duration STATS_WINDOW = Duration.ofHours(24);

    private final InvestigationRepository investigations;
    private final InvestigationCitationRepository citations;
    private final InvestigationReviewRepository reviews;
    private final PolicyDocumentRepository documents;
    private final PolicyChunkRepository chunks;
    private final PolicyRetriever retriever;
    private final ReportGenerator generator;
    private final AiProperties properties;

    public InvestigationQueryService(InvestigationRepository investigations,
                                     InvestigationCitationRepository citations,
                                     InvestigationReviewRepository reviews,
                                     PolicyDocumentRepository documents,
                                     PolicyChunkRepository chunks,
                                     PolicyRetriever retriever,
                                     ReportGenerator generator,
                                     AiProperties properties) {
        this.investigations = investigations;
        this.citations = citations;
        this.reviews = reviews;
        this.documents = documents;
        this.chunks = chunks;
        this.retriever = retriever;
        this.generator = generator;
        this.properties = properties;
    }

    // ---------------------------------------------------------- investigations

    public InvestigationView byId(String investigationId) {
        return InvestigationView.from(require(investigationId));
    }

    /**
     * A report with its citations, its evidence vector and every human verdict on it.
     *
     * <p>The dispute view. Four things together are what make a generated recommendation defensible: the
     * narrative, the exact inputs it was written from, which policy it was shown, and whether a person
     * agreed. Splitting them across four calls would let a caller assemble an inconsistent picture.
     */
    public InvestigationDetailView detail(String investigationId) {
        Investigation investigation = require(investigationId);

        return new InvestigationDetailView(
                InvestigationView.from(investigation),
                investigation.getFeatureSnapshot(),
                citations.findByInvestigationIdOrderByRankAsc(investigationId).stream()
                        .map(CitationView::from)
                        .toList(),
                reviews.findByInvestigationIdOrderByReviewedAtAsc(investigationId).stream()
                        .map(review -> ReviewView.from(review, investigation.getRecommendation()))
                        .toList());
    }

    /**
     * Every investigation on one payment, newest first.
     *
     * <p>Plural because an analyst can re-run one. Two reports on the same payment may legitimately
     * disagree if the corpus or the generator changed between them, and seeing them together is how
     * somebody works out why.
     */
    public List<InvestigationView> forPayment(String paymentId) {
        List<Investigation> found = investigations.findByPaymentIdOrderByCreatedAtDesc(paymentId);
        if (found.isEmpty()) {
            throw new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                    "No investigation exists for payment " + paymentId);
        }
        return found.stream().map(InvestigationView::from).toList();
    }

    /**
     * Browses investigations, newest first, optionally filtered.
     *
     * <p>Filters are mutually exclusive and applied in a fixed precedence. Combining them would need a
     * specification or a criteria query, and the queue this serves — "today's recommended rejections", or
     * "everything for this customer" — never asks for two at once.
     */
    public Page<Investigation> browse(Recommendation recommendation, String userId,
                                      Pageable pageable) {
        if (userId != null && !userId.isBlank()) {
            return investigations.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        }
        if (recommendation != null) {
            return investigations.findByRecommendationOrderByCreatedAtDesc(recommendation, pageable);
        }
        return investigations.findAllByOrderByCreatedAtDesc(pageable);
    }

    // ------------------------------------------------------- knowledge base

    /**
     * The corpus an investigation may cite.
     *
     * <p>Readable by analysts deliberately. A citation is only checkable if the cited document can be
     * opened, and a report that cites policy nobody can read is asking to be taken on trust — which is
     * exactly what the citation was supposed to remove.
     */
    public List<PolicyView> policies() {
        return documents.findAllByOrderByDocumentIdAsc().stream()
                .map(this::toPolicyView)
                .toList();
    }

    public PolicyDetailView policy(String documentId) {
        PolicyDocument document = documents.findByDocumentId(documentId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No fraud policy with id " + documentId));
        return new PolicyDetailView(toPolicyView(document), document.getBody());
    }

    private PolicyView toPolicyView(PolicyDocument document) {
        return new PolicyView(
                document.getDocumentId(),
                document.getTitle(),
                document.getCategory(),
                document.relatedRuleIdList(),
                document.getVersion(),
                document.getSource(),
                chunks.findByDocumentIdOrderByOrdinalAsc(document.getDocumentId()).size(),
                document.getUpdatedAt());
    }

    // ------------------------------------------------------------------ engine

    /**
     * What retrieval and generation are configured to do, and what they are capable of.
     *
     * <p>{@code semanticRetrieval} and {@code usableChunks} are the two fields worth reading. The first
     * says whether a null retrieval result means "no relevant policy" or merely "no shared vocabulary".
     * The second, at zero with a non-empty corpus, means everything was embedded by a previous embedder
     * and is invisible to retrieval — which produces no error and looks exactly like a working service.
     */
    public EngineInfoView engineInfo() {
        return new EngineInfoView(
                generator.id(),
                generator.isDeterministic(),
                retriever.embedderId(),
                retriever.isSemanticRetrieval(),
                properties.getRetrieval().getMinimumSimilarity(),
                properties.getRetrieval().getTopK(),
                documents.count(),
                retriever.usableChunkCount(),
                Arrays.stream(Recommendation.values()).map(Enum::name).toList());
    }

    // ------------------------------------------------------------------- stats

    public StatsView stats() {
        Instant since = Instant.now().minus(STATS_WINDOW);

        Map<Recommendation, Long> byRecommendation = new EnumMap<>(Recommendation.class);
        for (Object[] row : investigations.countByRecommendationSince(since)) {
            byRecommendation.put((Recommendation) row[0], ((Number) row[1]).longValue());
        }

        List<GeneratorSummary> byGenerator = investigations.summariseByGenerator(since).stream()
                .map(row -> new GeneratorSummary(
                        (String) row[0],
                        ((Number) row[1]).longValue(),
                        row[2] == null ? 0.0 : ((Number) row[2]).doubleValue(),
                        row[3] == null ? 0.0 : ((Number) row[3]).doubleValue()))
                .toList();

        List<AgreementSummary> agreement = reviews.agreementByRecommendationSince(since).stream()
                .map(row -> {
                    long total = ((Number) row[1]).longValue();
                    long agreed = row[2] == null ? 0L : ((Number) row[2]).longValue();
                    return new AgreementSummary((Recommendation) row[0], total, agreed,
                            total == 0 ? 0.0 : (double) agreed / total);
                })
                .toList();

        List<CitedDocument> cited = citations.countCitationsByDocumentSince(since).stream()
                .map(row -> new CitedDocument((String) row[0], ((Number) row[1]).longValue()))
                .toList();

        return new StatsView(
                investigations.countByCreatedAtAfter(since),
                investigations.countByRetrievedChunksAndCreatedAtAfter(0, since),
                investigations.countWithHallucinations(since),
                investigations.countByDeterministicFallbackAndCreatedAtAfter(true, since),
                investigations.averageGenerationLatencyMs(since),
                byRecommendation.entrySet().stream()
                        .map(entry -> new RecommendationCount(entry.getKey(), entry.getValue()))
                        .toList(),
                byGenerator,
                agreement,
                cited,
                Instant.now());
    }

    // -------------------------------------------------------------- internals

    private Investigation require(String investigationId) {
        return investigations.findByInvestigationId(investigationId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No investigation with id " + investigationId));
    }
}
