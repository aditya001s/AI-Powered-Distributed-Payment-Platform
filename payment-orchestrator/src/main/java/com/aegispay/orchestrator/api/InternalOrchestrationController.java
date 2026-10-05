package com.aegispay.orchestrator.api;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.security.Roles;
import com.aegispay.orchestrator.api.OrchestratorDtos.InjectFaultRequest;
import com.aegispay.orchestrator.api.OrchestratorDtos.SimulationStateView;
import com.aegispay.orchestrator.provider.PaymentProvider;
import com.aegispay.orchestrator.provider.ProviderHealthMonitor;
import com.aegispay.orchestrator.provider.ProviderRouter;
import com.aegispay.orchestrator.provider.SimulatedPaymentProvider;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Drives the simulated providers, and probes them on demand.
 *
 * <p><strong>This surface exists only because the providers are simulated.</strong> A real
 * integration would have nothing like it, and this controller would be deleted along with the
 * simulation. It changes what the <em>fake rail</em> does — never what the orchestrator does with the
 * answer, which is the behaviour actually under test.
 *
 * <p><strong>Not reachable from the internet.</strong> The gateway has no route for
 * {@code /internal/**} and denies the path outright, and every handler additionally requires a
 * client-credentials token carrying {@code ROLE_SERVICE}. Two independent controls, because an
 * endpoint that can make a payment rail start failing is worth protecting twice even in a sandbox.
 *
 * <h2>Why fault injection is exposed over HTTP at all</h2>
 *
 * <p>The alternative is reaching into the provider beans from inside the test JVM. That works for a
 * unit test and not for an integration test: the interesting behaviour — a breaker opening, a saga
 * falling back, an orphaned authorisation being discovered and voided — only appears when the
 * orchestrator is running as a real application against a real database and a real broker. An
 * integration test therefore needs a way to arm a fault in the running process, and this is it.
 */
@RestController
@RequestMapping("/internal/providers")
@Tag(name = "Internal provider simulation",
        description = "Arm faults on the simulated providers and probe them. Requires ROLE_SERVICE.")
@PreAuthorize("hasAuthority('" + Roles.ROLE_SERVICE + "')")
public class InternalOrchestrationController {

    private static final Logger log =
            LoggerFactory.getLogger(InternalOrchestrationController.class);

    private final ProviderRouter router;
    private final ProviderHealthMonitor healthMonitor;

    public InternalOrchestrationController(ProviderRouter router,
                                           ProviderHealthMonitor healthMonitor) {
        this.router = router;
        this.healthMonitor = healthMonitor;
    }

    @PostMapping("/{providerId}/simulate")
    @Operation(summary = "Arm a fault on a simulated provider",
            description = """
                    Deterministic, not probabilistic: the fault applies to the next `calls` calls and
                    then stops. A random failure rate would make every test of fallback, retry and
                    circuit breaking flaky, and would leave a genuine regression
                    indistinguishable from an unlucky seed.

                    The modes are not interchangeable, and the differences are the point:

                    * `UNREACHABLE` — the request never arrived, so retrying the same provider is
                      safe.
                    * `TIMEOUT` — the charge **did** go through and the acknowledgement was lost.
                      Not safe to retry; the saga must fall back and then find and void the
                      orphaned authorisation.
                    * `UPSTREAM_ERROR` — the provider answered with an error of its own.
                    * `DECLINE` — a working provider saying no. Must not count against its health.
                    * `SLOW` — answers correctly, slowly enough to trip slow-call detection.

                    Send `NONE` with any `calls` value to clear.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Fault armed"),
            @ApiResponse(responseCode = "400", description = "That provider is not simulated",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No such provider",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<SimulationStateView> simulate(
            @PathVariable @NotBlank @Size(max = 32) String providerId,
            @Valid @RequestBody InjectFaultRequest request) {

        SimulatedPaymentProvider provider = requireSimulated(providerId);

        provider.controls().injectFault(request.mode(), request.calls());
        provider.controls().setExtraLatencyMs(request.extraLatencyMs());

        log.warn("SIMULATION: {} will answer with {} for the next {} call(s), +{}ms latency",
                provider.id(), request.mode(),
                request.calls() < 0 ? "unlimited" : request.calls(), request.extraLatencyMs());

        return ResponseEntity.ok(SimulationStateView.from(provider));
    }

    @PostMapping("/{providerId}/simulate/reset")
    @Operation(summary = "Clear one provider's armed faults and outstanding authorisations",
            description = """
                    Returns the fake rail to a clean slate. Used between integration tests so a
                    fault armed by one cannot leak into the next and fail it for no visible reason.

                    Also drops the provider's simulated authorisations, which is why this is a reset
                    rather than just a clear: a leftover authorisation would make the next test's
                    idempotency-key lookup return a stale handle.""")
    @ApiResponse(responseCode = "404", description = "No such provider",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SimulationStateView> reset(
            @PathVariable @NotBlank @Size(max = 32) String providerId) {

        SimulatedPaymentProvider provider = requireSimulated(providerId);
        provider.resetSimulatedState();
        log.warn("SIMULATION: {} reset", provider.id());
        return ResponseEntity.ok(SimulationStateView.from(provider));
    }

    @GetMapping("/simulation")
    @Operation(summary = "What every simulated provider is currently rigged to do",
            description = "Read back before asserting, so a test that fails does so against the "
                    + "state it actually arranged rather than the one it meant to.")
    public ResponseEntity<List<SimulationStateView>> simulationState() {
        return ResponseEntity.ok(router.allProviders().stream()
                .filter(SimulatedPaymentProvider.class::isInstance)
                .map(SimulatedPaymentProvider.class::cast)
                .map(SimulationStateView::from)
                .toList());
    }

    @PostMapping("/probe")
    @Operation(summary = "Run the health sweep now",
            description = """
                    Normally scheduled. Exposed so a caller that has just armed or cleared a fault
                    does not have to wait a full interval for the health view to catch up, which is
                    otherwise the slowest part of any test that involves provider health.""")
    public ResponseEntity<List<ProviderHealthMonitor.ProviderHealth>> probeNow() {
        healthMonitor.probeAll();
        return ResponseEntity.ok(healthMonitor.currentHealth());
    }

    /**
     * Resolves a provider and insists it is a simulated one.
     *
     * <p>Two distinct failures, reported distinctly: an unknown id is a 404, while a real
     * integration that happens to be registered is a 400. Collapsing them would tell an operator
     * their provider does not exist when in fact it exists and simply cannot be rigged.
     */
    private SimulatedPaymentProvider requireSimulated(String providerId) {
        PaymentProvider provider = router.byId(providerId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No payment provider is registered with id " + providerId));

        if (!(provider instanceof SimulatedPaymentProvider simulated)) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    provider.id() + " is not a simulated provider, so its behaviour cannot "
                            + "be rigged");
        }
        return simulated;
    }
}
