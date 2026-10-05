import { api } from './client';
import type {
  AlertStatus,
  AlertView,
  AnalystDecisionView,
  AssessmentDetailView,
  DecisionAction,
  FraudStatsView,
  Granularity,
  HeldPaymentView,
  NotificationDeliveryView,
  NotificationPage,
  NotificationStatsView,
  OrchestrationStatsView,
  OverviewView,
  PageResponse,
  PaymentFactView,
  PaymentStatus,
  PerformanceView,
  ProjectionInfoView,
  ProviderHealthView,
  RiskDistributionView,
  RiskLevel,
  TimeSeriesView,
  TokenResponse,
  UserSummary,
} from './types';

/**
 * Every call the dashboard makes, in one place.
 *
 * Components import from here rather than assembling paths themselves. A path
 * typed inline is a path that drifts: the fraud service's alert queue is
 * `/api/fraud/alerts` and the analytics payment list is `/api/admin/analytics/payments`,
 * and getting those two confused produces a 404 rather than a compile error.
 *
 * Note what is *not* here: there is no analytics endpoint for alerts, providers or
 * notifications. The dashboard reads each of those from the service that owns it,
 * through the gateway. Re-serving them from analytics-service would create a
 * second, staler copy of a fraud alert, and showing an analyst an alert that was
 * already decided is exactly the kind of mistake this platform must not make.
 */

/** A window on the analytics endpoints. Absent bounds let the server pick its default day. */
export interface AnalyticsQuery {
  from?: string | undefined;
  to?: string | undefined;
  granularity?: Granularity | undefined;
}

function windowParams(query: AnalyticsQuery): Record<string, string | undefined> {
  return {
    from: query.from,
    to: query.to,
    granularity: query.granularity,
  };
}

// --------------------------------------------------------------------- auth

export const authApi = {
  /**
   * Signs in. Anonymous because a stale token on this request would be attached to
   * a credential exchange, and the gateway rate-limits this path by IP.
   */
  login: (email: string, password: string): Promise<TokenResponse> =>
    api.postAnonymous<TokenResponse>('/api/auth/login', { email, password }),

  /**
   * Exchanges a refresh token. Also anonymous: the access token it is replacing is
   * expired by definition, and sending it would only invite a 401 on the very
   * request meant to recover from one.
   */
  refresh: (refreshToken: string): Promise<TokenResponse> =>
    api.postAnonymous<TokenResponse>('/api/auth/refresh', { refreshToken }),

  /** Revokes the refresh token server-side. Best-effort; the client clears state regardless. */
  logout: (refreshToken: string): Promise<void> =>
    api.post<void>('/api/auth/logout', { refreshToken }),

  me: (signal?: AbortSignal): Promise<UserSummary> =>
    api.get<UserSummary>('/api/users/me', undefined, signal),
};

// ---------------------------------------------------------------- analytics

export const analyticsApi = {
  overview: (query: AnalyticsQuery, signal?: AbortSignal): Promise<OverviewView> =>
    api.get<OverviewView>('/api/admin/analytics/overview', windowParams(query), signal),

  timeseries: (query: AnalyticsQuery, signal?: AbortSignal): Promise<TimeSeriesView> =>
    api.get<TimeSeriesView>('/api/admin/analytics/timeseries', windowParams(query), signal),

  risk: (query: AnalyticsQuery, signal?: AbortSignal): Promise<RiskDistributionView> =>
    api.get<RiskDistributionView>('/api/admin/analytics/risk', windowParams(query), signal),

  performance: (query: AnalyticsQuery, signal?: AbortSignal): Promise<PerformanceView> =>
    api.get<PerformanceView>('/api/admin/analytics/performance', windowParams(query), signal),

  /**
   * The cross-customer payment list. This is the one read no other service offers:
   * payment-service scopes its queries to the calling customer, by design.
   */
  payments: (
    params: {
      from?: string | undefined;
      to?: string | undefined;
      status?: PaymentStatus | undefined;
      riskLevel?: RiskLevel | undefined;
      providerId?: string | undefined;
      userId?: string | undefined;
      /** Exact integer minor units. Never a decimal — the server compares against a BIGINT. */
      minAmountMinor?: number | undefined;
      /** 0-100. A floor rather than a band, so it can span HIGH and CRITICAL. */
      minRiskScore?: number | undefined;
      /** Verdicts reached without the ML model. Only sent when on; false and absent are the same query. */
      degradedOnly?: boolean | undefined;
      page?: number | undefined;
      size?: number | undefined;
      sort?: string | undefined;
    },
    signal?: AbortSignal,
  ): Promise<PageResponse<PaymentFactView>> =>
    api.get<PageResponse<PaymentFactView>>('/api/admin/analytics/payments', params, signal),

  payment: (paymentId: string, signal?: AbortSignal): Promise<PaymentFactView> =>
    api.get<PaymentFactView>(
      `/api/admin/analytics/payments/${encodeURIComponent(paymentId)}`,
      undefined,
      signal,
    ),

  /** What the read model is and is not derived from, plus per-stream lag. */
  projection: (signal?: AbortSignal): Promise<ProjectionInfoView> =>
    api.get<ProjectionInfoView>('/api/admin/analytics/projection', undefined, signal),
};

// -------------------------------------------------------------------- fraud

export const fraudApi = {
  alerts: (
    params: {
      blockingOnly?: boolean | undefined;
      status?: AlertStatus | undefined;
      page?: number | undefined;
      size?: number | undefined;
      sort?: string | undefined;
    },
    signal?: AbortSignal,
  ): Promise<PageResponse<AlertView>> =>
    api.get<PageResponse<AlertView>>('/api/fraud/alerts', params, signal),

  alert: (alertId: string, signal?: AbortSignal): Promise<AlertView> =>
    api.get<AlertView>(`/api/fraud/alerts/${encodeURIComponent(alertId)}`, undefined, signal),

  /** The audit trail for one alert: who decided what, when, and why. */
  alertDecisions: (alertId: string, signal?: AbortSignal): Promise<AnalystDecisionView[]> =>
    api.get<AnalystDecisionView[]>(
      `/api/fraud/alerts/${encodeURIComponent(alertId)}/decisions`,
      undefined,
      signal,
    ),

  /**
   * Claims an alert before working it. Returns 409 when somebody else got there
   * first, which is the point: two analysts deciding the same held payment from
   * two browsers is the race this prevents.
   */
  take: (alertId: string): Promise<AlertView> =>
    api.post<AlertView>(`/api/fraud/alerts/${encodeURIComponent(alertId)}/take`),

  /**
   * Approve or block. The reason is mandatory server-side and is recorded against
   * the analyst's own identity, taken from the token rather than the request body.
   *
   * This goes to fraud-service directly, not through analytics-service. The
   * decision belongs in the append-only trail of the service that raised the
   * alert, attributed to the analyst who made it; proxying it would insert a
   * service that played no part in the decision into its chain of custody.
   *
   * A 202-shaped truth worth knowing: this returns as soon as the decision is
   * durable. The payment itself resumes asynchronously, so the UI must not claim
   * the payment has completed.
   */
  decide: (alertId: string, action: DecisionAction, reason: string): Promise<AnalystDecisionView> =>
    api.post<AnalystDecisionView>(`/api/fraud/alerts/${encodeURIComponent(alertId)}/decide`, {
      action,
      reason,
    }),

  assessmentDetail: (paymentId: string, signal?: AbortSignal): Promise<AssessmentDetailView> =>
    api.get<AssessmentDetailView>(
      `/api/fraud/assessments/${encodeURIComponent(paymentId)}/detail`,
      undefined,
      signal,
    ),

  stats: (signal?: AbortSignal): Promise<FraudStatsView> =>
    api.get<FraudStatsView>('/api/fraud/stats', undefined, signal),
};

// ------------------------------------------------------------ orchestration

export const orchestrationApi = {
  /**
   * Payments whose funds are reserved pending a human decision. Sourced from the
   * orchestrator because it owns the saga state, and `waitingSeconds` there is
   * measured against the live saga rather than a projection that might lag.
   */
  held: (signal?: AbortSignal): Promise<HeldPaymentView[]> =>
    api.get<HeldPaymentView[]>('/api/orchestration/sagas/held', undefined, signal),

  stats: (signal?: AbortSignal): Promise<OrchestrationStatsView> =>
    api.get<OrchestrationStatsView>('/api/orchestration/stats', undefined, signal),

  providers: (signal?: AbortSignal): Promise<ProviderHealthView[]> =>
    api.get<ProviderHealthView[]>('/api/orchestration/providers', undefined, signal),

  /** ADMIN only. A reason is required: this is a manual override of routing. */
  disableProvider: (providerId: string, reason: string): Promise<ProviderHealthView> =>
    api.post<ProviderHealthView>(
      `/api/orchestration/providers/${encodeURIComponent(providerId)}/disable`,
      { reason },
    ),

  /** ADMIN only. Clears the kill switch; the circuit breaker still decides routability. */
  enableProvider: (providerId: string): Promise<ProviderHealthView> =>
    api.post<ProviderHealthView>(
      `/api/orchestration/providers/${encodeURIComponent(providerId)}/enable`,
    ),
};

// ------------------------------------------------------------ notifications

export const notificationApi = {
  /**
   * Notifications that exhausted every retry. Each row is a customer who was never
   * told something the platform decided to tell them.
   *
   * Returns the notification module's own envelope, not `PageResponse` — see the
   * type. Wishful typing here would surface as `undefined.map` in the table.
   */
  deadLetters: (
    params: { page?: number | undefined; size?: number | undefined },
    signal?: AbortSignal,
  ): Promise<NotificationPage<NotificationDeliveryView>> =>
    api.get<NotificationPage<NotificationDeliveryView>>(
      '/api/admin/notifications/dead-letters',
      params,
      signal,
    ),

  /** Re-queues one dead letter. Idempotent server-side; a double click cannot double-send. */
  replay: (deliveryId: string): Promise<NotificationDeliveryView> =>
    api.post<NotificationDeliveryView>(
      `/api/admin/notifications/dead-letters/${encodeURIComponent(deliveryId)}/replay`,
    ),

  stats: (signal?: AbortSignal): Promise<NotificationStatsView> =>
    api.get<NotificationStatsView>('/api/admin/notifications/stats', undefined, signal),
};
