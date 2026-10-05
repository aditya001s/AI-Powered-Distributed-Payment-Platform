/**
 * Wire types, mirroring the backend DTOs.
 *
 * Hand-written rather than generated from the OpenAPI documents. Generation would
 * be better at scale, but it needs every service running to produce the spec, and
 * a build that cannot run offline is a build that eventually does not run. These
 * are narrow: only the fields this dashboard actually reads.
 *
 * Nullability is copied faithfully from the server, and that is the part worth
 * caring about. `successRate` is null when nothing settled, `modelVersion` is null
 * in degraded mode, `lagSeconds` is null before anything is projected. Typing
 * those as non-null would let a tile render "NaN%" to somebody deciding whether to
 * release a customer's payment.
 */

// ---------------------------------------------------------------- primitives

/** An ISO-8601 instant, as the platform serialises every timestamp. */
export type IsoInstant = string;

/**
 * A monetary amount.
 *
 * `amountMinor` is the exact integer value and is what all arithmetic uses.
 * `amount` is a pre-formatted decimal string from the server, kept only for
 * display. Doing sums on the decimal in JavaScript would reintroduce the
 * floating-point error the whole platform avoids by storing minor units.
 */
export interface MoneyView {
  amount: string;
  amountMinor: number;
  currency: string;
}

export type PaymentStatus =
  | 'CREATED'
  | 'FUNDS_RESERVED'
  | 'RISK_ASSESSED'
  | 'ON_HOLD'
  | 'AUTHORIZED'
  | 'CAPTURED'
  | 'COMPLETED'
  | 'REJECTED'
  | 'FAILED'
  | 'REFUNDED'
  | 'PARTIALLY_REFUNDED';

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export type FraudDecision = 'APPROVE' | 'REVIEW' | 'HOLD' | 'REJECT';

export type AlertStatus = 'OPEN' | 'IN_REVIEW' | 'RESOLVED' | 'DISMISSED';

/** RELEASE lets the payment continue; REJECT refuses it and releases the funds. */
export type DecisionAction = 'RELEASE' | 'REJECT';

export type Granularity = 'minute' | 'hour' | 'day' | 'week';

/** The platform's error body. Identical from the gateway and from every service. */
export interface ApiErrorBody {
  timestamp?: IsoInstant;
  status: number;
  code: string;
  message: string;
  path?: string;
  correlationId?: string;
  fieldErrors?: Array<{ field: string; message: string; rejectedValue?: unknown }>;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

// --------------------------------------------------------------------- auth

export interface UserSummary {
  userId: string;
  email: string;
  fullName: string;
  roles: string[];
  status: string;
  kycLevel: string;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  /** Seconds. Used to refresh ahead of expiry rather than after a 401. */
  expiresIn: number;
  refreshExpiresIn: number;
  user: UserSummary;
}

// ---------------------------------------------------------------- analytics

/**
 * How current the read model is.
 *
 * Rendered on every page that shows a projected number. A projection that stops
 * updating does not go blank — it keeps serving the last figures it had, so
 * without this an operator cannot tell "no held payments" from "the consumer died
 * three hours ago".
 */
export interface FreshnessView {
  newestEventAt: IsoInstant | null;
  lagSeconds: number | null;
  stale: boolean;
  streams: StreamView[];
}

export interface StreamView {
  topic: string;
  lastEventAt: IsoInstant;
  lastAppliedAt: IsoInstant;
  lagSeconds: number;
  stale: boolean;
}

export interface WindowView {
  from: IsoInstant;
  to: IsoInstant;
  granularity: string;
}

export interface OverviewView {
  window: WindowView;
  freshness: FreshnessView;
  payments: number;
  completed: number;
  failed: number;
  rejected: number;
  refunded: number;
  inFlight: number;
  onHold: number;
  /** Verdicts reached without the fraud model. A LOW band containing these means "nothing was found". */
  degradedVerdicts: number;
  fellBack: number;
  highRisk: number;
  /** Null when nothing reached a terminal state — which is not the same as a zero rate. */
  successRate: number | null;
  settled: MoneyView;
  fees: MoneyView;
  refundedAmount: MoneyView;
}

export interface TimeBucketView {
  bucketStart: IsoInstant;
  payments: number;
  completed: number;
  unsuccessful: number;
  highRisk: number;
  degradedVerdicts: number;
  settled: MoneyView;
}

export interface TimeSeriesView {
  window: WindowView;
  freshness: FreshnessView;
  buckets: TimeBucketView[];
}

export interface RiskBandView {
  /** A risk level, or 'UNSCORED' for a payment rejected before it was scored. */
  riskLevel: RiskLevel | 'UNSCORED';
  payments: number;
  completed: number;
  onHold: number;
  rejected: number;
  degradedVerdicts: number;
  amount: MoneyView;
}

export interface ScoreBucketView {
  bucketFloor: number;
  payments: number;
  completed: number;
  stopped: number;
}

export interface RiskDistributionView {
  window: WindowView;
  freshness: FreshnessView;
  bands: RiskBandView[];
  histogram: ScoreBucketView[];
  degradedVerdicts: number;
  unscored: number;
}

export interface LatencyView {
  /** Read this before the percentiles. A p99 over four samples is not a p99. */
  samples: number;
  p50: number;
  p95: number;
  p99: number;
  worst: number;
}

export interface ProviderShareView {
  providerId: string;
  payments: number;
  completed: number;
  fellBack: number;
  settled: MoneyView;
  averageLatencyMs: number;
}

export interface FailureShareView {
  failureCode: string;
  payments: number;
}

export interface PerformanceView {
  window: WindowView;
  freshness: FreshnessView;
  latency: LatencyView;
  providers: ProviderShareView[];
  failures: FailureShareView[];
}

export interface PaymentFactView {
  paymentId: string;
  transactionId: string | null;
  initiatorUserId: string | null;
  destinationUserId: string | null;
  status: PaymentStatus;
  paymentType: string | null;
  amount: MoneyView | null;
  fee: MoneyView | null;
  refunded: MoneyView | null;
  riskLevel: RiskLevel | null;
  fraudDecision: FraudDecision | null;
  riskScore: number | null;
  degradedMode: boolean;
  modelVersion: string | null;
  ruleHits: string[];
  providerId: string | null;
  providerAttempts: number | null;
  ledgerTransactionId: string | null;
  failureCode: string | null;
  failureReason: string | null;
  endToEndLatencyMs: number | null;
  createdAt: IsoInstant;
  completedAt: IsoInstant | null;
  firstSeenAt: IsoInstant;
}

export interface ProjectionInfoView {
  freshness: FreshnessView;
  projectedTopics: string[];
  notProjected: string[];
  paymentsProjected: number;
  staleAfterSeconds: number;
  derivedAggregates: boolean;
}

// -------------------------------------------------------------------- fraud

export interface AlertView {
  alertId: string;
  paymentId: string;
  assessmentId: string;
  initiatorUserId: string;
  riskLevel: RiskLevel;
  riskScore: number;
  decision: FraudDecision;
  summary: string;
  status: AlertStatus;
  assignedTo: string | null;
  /** True when a customer's funds are reserved waiting on this. Orders the queue ahead of score. */
  blocksPayment: boolean;
  createdAt: IsoInstant;
  resolvedAt: IsoInstant | null;
}

export interface AssessmentView {
  assessmentId: string;
  paymentId: string;
  transactionId: string;
  fraudProbability: number;
  riskScore: number;
  riskLevel: RiskLevel;
  decision: FraudDecision;
  amount: MoneyView;
  reasons: string[];
  ruleHits: string[];
  /** Null in degraded mode: nothing was scored, so there is no model to name. */
  modelVersion: string | null;
  scoringLatencyMs: number;
  degradedMode: boolean;
  evaluatedAt: IsoInstant;
}

export interface AnalystDecisionView {
  decisionId: string;
  alertId: string;
  paymentId: string;
  action: DecisionAction;
  reason: string;
  /** Taken from the analyst's token, never from the request. */
  decidedBy: string;
  decidedAt: IsoInstant;
}

export interface AssessmentDetailView {
  assessment: AssessmentView;
  /** JSON. Null in degraded mode: nothing was scored, so there is no vector. */
  featureSnapshot: string | null;
  decisions: AnalystDecisionView[];
}

export interface FraudStatsView {
  assessments24h: number;
  approvals24h: number;
  reviews24h: number;
  holds24h: number;
  rejections24h: number;
  degradedAssessments24h: number;
  openAlerts: number;
  /** Every one is a customer whose funds are reserved waiting on a human. */
  blockingAlerts: number;
  averageScoringLatencyMs: number;
  takenAt: IsoInstant;
}

// ------------------------------------------------------------ orchestration

export interface HeldPaymentView {
  sagaId: string;
  paymentId: string;
  initiatorUserId: string;
  amount: MoneyView;
  riskLevel: RiskLevel;
  fraudDecision: FraudDecision;
  fraudProbability: number | null;
  riskAssessmentId: string | null;
  riskDegraded: boolean;
  holdId: string | null;
  riskHeldAt: IsoInstant;
  /** How long this customer has been unable to spend their own money. */
  waitingSeconds: number | null;
}

export interface ProviderHealthView {
  providerId: string;
  displayName: string;
  /** The operator kill switch. Survives restarts. */
  enabled: boolean;
  observedHealth: string;
  /** What routing actually reads, as opposed to the last probe result. */
  circuitBreakerState: string;
  breakerFailureRate: number;
  lastLatencyMs: number | null;
  consecutiveFailures: number;
  lastCheckedAt: IsoInstant | null;
  disabledReason: string | null;
  /** The combined answer, so nobody has to reason about the other three under pressure. */
  routable: boolean;
  feeBasisPoints: number;
  supportedCurrencies: string[];
  maxAmountMinor: number;
}

export interface OrchestrationStatsView {
  inFlight: number;
  completed: number;
  failed: number;
  rejected: number;
  heldForReview: number;
  /** Non-zero means the review queue is not being worked, or the decision consumer stopped. */
  heldOverAnHour: number;
  /** Expected to be zero. Each one is money that may be stranded. */
  uncompensated: number;
  fallbackCount: number;
  takenAt: IsoInstant;
}

// ------------------------------------------------------------ notifications

export interface NotificationDeliveryView {
  deliveryId: string;
  channel: 'EMAIL' | 'SMS' | 'PUSH' | 'IN_APP';
  status: 'PENDING' | 'SENT' | 'FAILED' | 'SUPPRESSED' | 'DEAD_LETTERED';
  /** Masked by the server, e.g. a***@example.com. */
  destination: string | null;
  suppressedReason: string | null;
  attemptCount: number;
  nextAttemptAt: IsoInstant | null;
  lastError: string | null;
  sentAt: IsoInstant | null;
  readAt: IsoInstant | null;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
}

export interface NotificationStatsView {
  notifications24h: number;
  deliveries24h: number;
  /** Each one is a customer who was never told something the platform decided to tell them. */
  deadLettered24h: number;
  deadLetteredTotal: number;
  retryBacklog: number;
  byChannelAndStatus: Record<string, number>;
}

/**
 * The notification admin endpoints return a hand-rolled envelope rather than
 * `PageResponse`. Typed as it actually is rather than as it ought to be — a
 * mismatch here would surface as `undefined.map` in a component.
 */
export interface NotificationPage<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
}
