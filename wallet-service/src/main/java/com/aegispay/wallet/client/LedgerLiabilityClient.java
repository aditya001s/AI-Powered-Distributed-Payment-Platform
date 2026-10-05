package com.aegispay.wallet.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.aegispay.common.exception.DomainExceptions.UpstreamUnavailableException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;

/** Reads the ledger's service-authenticated customer-liability snapshot. */
@Component
public class LedgerLiabilityClient {

    private final RestClient client;
    private final String baseUrl;

    public LedgerLiabilityClient(@Qualifier("internalRestClient") RestClient client,
                                 @Value("${aegis.clients.ledger-service-url:http://localhost:8083}")
                                 String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public LiabilitySnapshot fetch(String currency) {
        try {
            LiabilitySnapshot response = client.get()
                    .uri(baseUrl + "/internal/ledger/wallet-liabilities?currency={currency}", currency)
                    .retrieve()
                    .body(LiabilitySnapshot.class);
            if (response == null || response.totalCustomerLiability() == null
                    || response.currency() == null || response.takenAt() == null) {
                throw new UpstreamUnavailableException("ledger-service",
                        "returned an incomplete wallet-liability snapshot");
            }
            return response;
        } catch (com.aegispay.common.exception.AegisException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new UpstreamUnavailableException("ledger-service",
                    "could not read the wallet-liability snapshot", ex);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LiabilitySnapshot(String currency, MoneyView totalCustomerLiability,
                                    Instant takenAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MoneyView(long amountMinor, String currency) {
    }
}
