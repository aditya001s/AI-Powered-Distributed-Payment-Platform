import type { ReactElement } from 'react';
import type {
  AlertStatus,
  FraudDecision,
  PaymentStatus,
  RiskLevel,
} from '../api/types';
import { humanise } from '../format';

/**
 * Coloured labels for the platform's enumerations.
 *
 * Every mapping below is a total function over its enum, written as an exhaustive
 * switch on purpose. When a new `PaymentStatus` is added server-side, these fail to
 * compile rather than quietly rendering the new value in the default grey — and a
 * new terminal status showing up as neutral grey on a queue of held payments is
 * precisely the kind of thing nobody notices until it matters.
 *
 * Colour is never the only signal. Each badge also carries its text, because a
 * red/green distinction is invisible to a substantial share of the people who will
 * read this screen all day.
 */

type Tone = 'neutral' | 'info' | 'good' | 'warn' | 'bad' | 'critical';

function paymentTone(status: PaymentStatus): Tone {
  switch (status) {
    case 'CREATED':
    case 'FUNDS_RESERVED':
    case 'RISK_ASSESSED':
    case 'AUTHORIZED':
    case 'CAPTURED':
      return 'info';
    case 'ON_HOLD':
      // Amber, not red. A hold is not a failure — it is a customer waiting on a
      // human, and it is the state most likely to need action.
      return 'warn';
    case 'COMPLETED':
      return 'good';
    case 'REJECTED':
      return 'bad';
    case 'FAILED':
      return 'critical';
    case 'REFUNDED':
    case 'PARTIALLY_REFUNDED':
      return 'neutral';
  }
}

function riskTone(level: RiskLevel | 'UNSCORED'): Tone {
  switch (level) {
    case 'LOW':
      return 'good';
    case 'MEDIUM':
      return 'info';
    case 'HIGH':
      return 'warn';
    case 'CRITICAL':
      return 'critical';
    case 'UNSCORED':
      // Deliberately not styled like LOW. An unscored payment was never assessed;
      // treating it as low risk is the single most dangerous thing this UI could do.
      return 'neutral';
  }
}

function decisionTone(decision: FraudDecision): Tone {
  switch (decision) {
    case 'APPROVE':
      return 'good';
    case 'REVIEW':
      return 'info';
    case 'HOLD':
      return 'warn';
    case 'REJECT':
      return 'bad';
  }
}

function alertTone(status: AlertStatus): Tone {
  switch (status) {
    case 'OPEN':
      return 'warn';
    case 'IN_REVIEW':
      return 'info';
    case 'RESOLVED':
      return 'good';
    case 'DISMISSED':
      return 'neutral';
  }
}

function Badge({ tone, label, title }: { tone: Tone; label: string; title?: string }): ReactElement {
  return (
    <span className={`badge badge--${tone}`} {...(title ? { title } : {})}>
      {label}
    </span>
  );
}

export function PaymentStatusBadge({ status }: { status: PaymentStatus }): ReactElement {
  return <Badge tone={paymentTone(status)} label={humanise(status)} />;
}

export function RiskBadge({
  level,
  score,
}: {
  level: RiskLevel | 'UNSCORED' | null;
  score?: number | null;
}): ReactElement {
  if (level === null) {
    // Null means fraud-checked has not been projected for this payment yet, which is
    // different from a verdict of UNSCORED. Saying "not assessed" rather than
    // guessing keeps those apart.
    return <Badge tone="neutral" label="Not assessed" title="No risk verdict projected yet" />;
  }
  const label =
    score === null || score === undefined ? humanise(level) : `${humanise(level)} ${score}`;
  return <Badge tone={riskTone(level)} label={label} />;
}

export function DecisionBadge({ decision }: { decision: FraudDecision }): ReactElement {
  return <Badge tone={decisionTone(decision)} label={humanise(decision)} />;
}

export function AlertStatusBadge({ status }: { status: AlertStatus }): ReactElement {
  return <Badge tone={alertTone(status)} label={humanise(status)} />;
}

/**
 * Marks a verdict reached without the fraud model.
 *
 * Worth its own badge because of what it means downstream: the platform is
 * configured not to reject when the model is unavailable, so these payments were
 * allowed through on rules alone. A LOW band full of degraded verdicts does not mean
 * "low risk", it means "nothing was found by the half of the system that was working".
 */
export function DegradedBadge({ degraded }: { degraded: boolean }): ReactElement | null {
  if (!degraded) {
    return null;
  }
  return (
    <Badge
      tone="warn"
      label="Degraded"
      title="Scored without the ML model — rules only. Not the same as low risk."
    />
  );
}

/** Marks a payment that reserves a customer's funds while it waits on a decision. */
export function BlockingBadge({ blocking }: { blocking: boolean }): ReactElement | null {
  if (!blocking) {
    return null;
  }
  return (
    <Badge
      tone="critical"
      label="Blocking"
      title="Funds are reserved. The customer cannot spend this money until a decision is made."
    />
  );
}
