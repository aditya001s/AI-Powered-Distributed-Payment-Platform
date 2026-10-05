package com.aegispay.orchestrator.provider;

/**
 * A provider failed to answer.
 *
 * <p>Deliberately a plain {@link RuntimeException} rather than one of the platform's
 * {@code AegisException} types. Every domain exception is on the shared
 * {@code ignoreExceptions} list of the circuit breaker and retry configurations — correctly, since
 * those represent business outcomes — so throwing one here would make provider faults invisible to
 * exactly the machinery built to react to them. This type is what the breaker counts.
 *
 * <p>A <strong>decline</strong> must never be signalled with this exception. A provider that
 * refuses a charge has worked correctly, and reporting that as a fault would trip its breaker and
 * take a healthy rail out of service because a run of customers were short of funds.
 */
public class ProviderFaultException extends RuntimeException {

    private final String providerId;
    private final String faultCode;

    /**
     * Whether the fault is known to have occurred before the request reached the provider.
     *
     * <p>The distinction decides whether the same provider may be retried. A connection refused is
     * safe to retry: nothing was submitted. A read timeout is <em>not</em>: the charge may have
     * been authorised and the response lost, so retrying risks a double authorisation. In that
     * case the saga falls back to another provider and voids whatever the first may have left
     * behind.
     */
    private final boolean safeToRetrySameProvider;

    public ProviderFaultException(String providerId, String faultCode, String message,
                                  boolean safeToRetrySameProvider) {
        super(message);
        this.providerId = providerId;
        this.faultCode = faultCode;
        this.safeToRetrySameProvider = safeToRetrySameProvider;
    }

    public ProviderFaultException(String providerId, String faultCode, String message,
                                  boolean safeToRetrySameProvider, Throwable cause) {
        super(message, cause);
        this.providerId = providerId;
        this.faultCode = faultCode;
        this.safeToRetrySameProvider = safeToRetrySameProvider;
    }

    /** The request never reached the provider, so nothing was submitted. */
    public static ProviderFaultException unreachable(String providerId, String message) {
        return new ProviderFaultException(providerId, "PROVIDER_UNREACHABLE", message, true);
    }

    /**
     * The provider was reached but did not answer in time.
     *
     * <p>Not safe to retry on the same provider: the charge may have gone through and only the
     * acknowledgement was lost.
     */
    public static ProviderFaultException timedOut(String providerId, String message) {
        return new ProviderFaultException(providerId, "PROVIDER_TIMEOUT", message, false);
    }

    /** The provider answered with an error of its own. */
    public static ProviderFaultException upstreamError(String providerId, String message) {
        return new ProviderFaultException(providerId, "PROVIDER_ERROR", message, false);
    }

    public String providerId() {
        return providerId;
    }

    public String faultCode() {
        return faultCode;
    }

    public boolean isSafeToRetrySameProvider() {
        return safeToRetrySameProvider;
    }
}
