package com.aegispay.common.web;

import com.aegispay.common.correlation.CorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds a correlation ID to every inbound request and echoes it back.
 *
 * <p>Ordered {@link Ordered#HIGHEST_PRECEDENCE} so that even security failures
 * and rejected requests are logged with an ID the caller can quote in a support
 * ticket.
 *
 * <p>The ID is taken from the {@code X-Correlation-Id} header when the caller
 * (normally the gateway) supplies one, so a single ID spans the whole request
 * tree; otherwise one is minted here.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            CorrelationContext.set(request.getHeader(CorrelationContext.HEADER));
            String correlationId = CorrelationContext.getOrCreate();
            response.setHeader(CorrelationContext.HEADER, correlationId);
            chain.doFilter(request, response);
        } finally {
            // Servlet containers pool threads; leaking MDC would mislabel the
            // next unrelated request handled by this thread.
            CorrelationContext.clear();
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
