import { useCallback, useMemo, useState } from 'react';
import type { ReactElement } from 'react';
import { ApiError } from '../api/client';
import { fraudApi, orchestrationApi } from '../api/endpoints';
import type { AlertStatus, AlertView, DecisionAction, HeldPaymentView } from '../api/types';
import { DataTable, Pager, type Column } from '../components/DataTable';
import { LoadError, PageHeader, Panel } from '../components/Layout';
import {
  AlertStatusBadge,
  BlockingBadge,
  DecisionBadge,
  DegradedBadge,
  RiskBadge,
} from '../components/StatusBadge';
import { formatDateTime, formatDuration, formatMoney, humanise, shortId } from '../format';
import { describeError, useLoader } from '../hooks/useLoader';

/** The server's own limit. Enforced here too so the operator is told before submitting. */
const REASON_LIMIT = 512;

const PAGE_SIZE = 20;

/**
 * The alert queue, and the one place this dashboard changes anything.
 *
 * ## Where the decision goes
 *
 * Straight to fraud-service: `POST /api/fraud/alerts/{id}/decide`. Not through
 * analytics-service, which knows nothing about this alert and played no part in the
 * decision. Routing it through a reporting service would insert an extra hop into the
 * chain of custody of a record that has to stand up to being audited, and the identity
 * recorded against the decision would be one service removed from the analyst who made
 * it. As it stands, `decidedBy` comes from the analyst's own token.
 *
 * ## Claim, then decide
 *
 * `take` is called before `decide`. Two analysts working the same queue from two
 * browsers is not a hypothetical, and the second one to press the button on an
 * already-decided alert gets a 409 rather than overwriting the first decision. Taking
 * the alert first makes that collision happen at the point of *starting* work rather
 * than after somebody has typed a paragraph of justification.
 *
 * ## What the success message may not say
 *
 * The endpoint returns as soon as the decision is durable. The payment itself resumes
 * asynchronously, through an outbox event the orchestrator consumes. So the UI says
 * the decision was recorded and the payment will resume — never that the payment
 * completed, which it cannot know.
 */
export function AlertsPage(): ReactElement {
  const [blockingOnly, setBlockingOnly] = useState(true);
  const [status, setStatus] = useState<AlertStatus | ''>('OPEN');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<AlertView | null>(null);

  const alerts = useLoader(
    (signal) =>
      fraudApi.alerts(
        {
          // Sent explicitly as false rather than omitted. To the fraud service a
          // `blockingOnly=false` filter and an absent one are different queries.
          blockingOnly,
          ...(status === '' ? {} : { status }),
          page,
          size: PAGE_SIZE,
          // Oldest first. A queue worked newest-first leaves the customer who has
          // been waiting longest waiting longer still.
          sort: 'createdAt,asc',
        },
        signal,
      ),
    [blockingOnly, status, page],
    60_000,
  );

  /*
   * Held sagas, for the waiting time.
   *
   * The alert knows when it was raised; the orchestrator knows how long the customer's
   * funds have actually been reserved, measured against the live saga rather than a
   * projection that may lag. That is the number that says how urgent this is.
   */
  const held = useLoader((signal) => orchestrationApi.held(signal), [], 60_000);

  const heldByPayment = useMemo(() => {
    const index = new Map<string, HeldPaymentView>();
    for (const entry of held.state.data ?? []) {
      index.set(entry.paymentId, entry);
    }
    return index;
  }, [held.state.data]);

  const afterDecision = useCallback(() => {
    setSelected(null);
    alerts.reload();
    held.reload();
  }, [alerts, held]);

  const columns: ReadonlyArray<Column<AlertView>> = useMemo(
    () => [
      {
        key: 'risk',
        header: 'Risk',
        render: (alert) => (
          <span className="cell-stack">
            <RiskBadge level={alert.riskLevel} score={alert.riskScore} />
            <BlockingBadge blocking={alert.blocksPayment} />
          </span>
        ),
      },
      {
        key: 'payment',
        header: 'Payment',
        render: (alert) => (
          <span className="cell-stack">
            <code title={alert.paymentId}>{shortId(alert.paymentId)}</code>
            <span className="cell-sub">{formatMoney(heldByPayment.get(alert.paymentId)?.amount)}</span>
          </span>
        ),
      },
      {
        key: 'waiting',
        header: 'Waiting',
        numeric: true,
        render: (alert) => {
          const entry = heldByPayment.get(alert.paymentId);
          if (!entry) {
            // No live saga: either it is not blocking, or it has already been
            // resolved and the alert list has not caught up. Saying "not held" is
            // honest; showing an age would imply funds are still reserved.
            return <span className="cell-sub">not held</span>;
          }
          const seconds = entry.waitingSeconds ?? 0;
          return (
            <span className={seconds > 3_600 ? 'cell-urgent' : ''}>
              {formatDuration(entry.waitingSeconds)}
            </span>
          );
        },
      },
      {
        key: 'decision',
        header: 'Engine verdict',
        render: (alert) => (
          <span className="cell-stack">
            <DecisionBadge decision={alert.decision} />
            <DegradedBadge degraded={heldByPayment.get(alert.paymentId)?.riskDegraded ?? false} />
          </span>
        ),
      },
      {
        key: 'summary',
        header: 'Why',
        secondary: true,
        render: (alert) => <span className="cell-summary">{alert.summary}</span>,
      },
      {
        key: 'status',
        header: 'Status',
        render: (alert) => (
          <span className="cell-stack">
            <AlertStatusBadge status={alert.status} />
            {alert.assignedTo ? (
              <span className="cell-sub" title={alert.assignedTo}>
                claimed by {shortId(alert.assignedTo, 10)}
              </span>
            ) : null}
          </span>
        ),
      },
      {
        key: 'raised',
        header: 'Raised',
        secondary: true,
        render: (alert) => formatDateTime(alert.createdAt),
      },
      {
        key: 'actions',
        header: 'Action',
        render: (alert) =>
          alert.status === 'RESOLVED' || alert.status === 'DISMISSED' ? (
            <span className="cell-sub">decided</span>
          ) : (
            <button
              className="button button--primary"
              type="button"
              onClick={() => setSelected(alert)}
            >
              Review
            </button>
          ),
      },
    ],
    [heldByPayment],
  );

  const data = alerts.state.data;

  return (
    <div className="page">
      <PageHeader
        title="Alert queue"
        description="Payments the fraud engine will not decide alone. Releasing or rejecting here is recorded against your account and cannot be edited afterwards."
      >
        <div className="filters">
          <label className="checkbox">
            <input
              type="checkbox"
              checked={blockingOnly}
              onChange={(event) => {
                setBlockingOnly(event.target.checked);
                setPage(0);
              }}
            />
            Blocking only
          </label>

          <label className="window-picker__label" htmlFor="alert-status">
            Status
          </label>
          <select
            id="alert-status"
            className="select"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as AlertStatus | '');
              setPage(0);
            }}
          >
            <option value="OPEN">Open</option>
            <option value="IN_REVIEW">In review</option>
            <option value="RESOLVED">Resolved</option>
            <option value="DISMISSED">Dismissed</option>
            <option value="">All</option>
          </select>

          <button className="button button--quiet" type="button" onClick={alerts.reload}>
            {alerts.refreshing ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </PageHeader>

      {held.state.status === 'failed' ? (
        // Degraded, not broken: the queue is still workable without the waiting times,
        // and hiding the whole page because a secondary read failed would be worse.
        <LoadError
          message={`Waiting times unavailable — ${describeError(held.state.error)}`}
          onRetry={held.reload}
        />
      ) : null}

      <Panel
        title={blockingOnly ? 'Blocking alerts' : 'All alerts'}
        description={
          blockingOnly
            ? 'Every row is a customer whose funds are reserved until somebody decides. Oldest first.'
            : 'Includes alerts raised for review that are not holding a payment.'
        }
      >
        <DataTable
          caption="Fraud alerts awaiting or holding a decision"
          columns={columns}
          rows={data?.content ?? []}
          rowKey={(alert) => alert.alertId}
          loading={alerts.state.status === 'loading'}
          error={
            alerts.state.status === 'failed' && !data ? describeError(alerts.state.error) : null
          }
          onRetry={alerts.reload}
          rowClassName={(alert) => (alert.blocksPayment ? 'table__row--blocking' : undefined)}
          empty={
            blockingOnly
              ? 'No payments are being held. Nothing needs a decision.'
              : 'No alerts match this filter.'
          }
        />

        {data ? (
          <Pager
            page={data.page}
            size={data.size}
            totalElements={data.totalElements}
            hasNext={!data.last}
            onPage={setPage}
            busy={alerts.refreshing}
          />
        ) : null}
      </Panel>

      {selected ? (
        <ReviewDialog
          alert={selected}
          held={heldByPayment.get(selected.paymentId) ?? null}
          onClose={() => setSelected(null)}
          onDecided={afterDecision}
        />
      ) : null}
    </div>
  );
}

type Phase = 'idle' | 'claiming' | 'deciding' | 'done';

/**
 * The review panel: the evidence, then the decision.
 *
 * The evidence is shown before the buttons deliberately. An analyst who has to click
 * to see why the engine flagged something will eventually stop clicking.
 */
function ReviewDialog({
  alert,
  held,
  onClose,
  onDecided,
}: {
  alert: AlertView;
  held: HeldPaymentView | null;
  onClose: () => void;
  onDecided: () => void;
}): ReactElement {
  const [action, setAction] = useState<DecisionAction | null>(null);
  const [reason, setReason] = useState('');
  const [phase, setPhase] = useState<Phase>('idle');
  const [error, setError] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);

  const detail = useLoader(
    (signal) => fraudApi.assessmentDetail(alert.paymentId, signal),
    [alert.paymentId],
  );

  const trimmed = reason.trim();
  const tooLong = trimmed.length > REASON_LIMIT;
  const submittable = action !== null && trimmed.length > 0 && !tooLong && phase === 'idle';

  async function submit(): Promise<void> {
    if (!action || !submittable) {
      return;
    }
    setError(null);

    /*
     * Claim first.
     *
     * A 409 here means somebody else is already working this alert, or it has been
     * resolved since the list was loaded. Finding that out now — before the decision
     * is submitted — is the entire point: the alternative is two analysts writing
     * contradictory justifications for the same held payment.
     */
    setPhase('claiming');
    try {
      await fraudApi.take(alert.alertId);
    } catch (caught) {
      setPhase('idle');
      if (caught instanceof ApiError && caught.isConflict) {
        setError(
          'Another analyst has already claimed or decided this alert. Refresh the queue to see the current state.',
        );
        return;
      }
      if (caught instanceof ApiError && caught.isNotFound) {
        setError('This alert no longer exists. Refresh the queue.');
        return;
      }
      setError(caught instanceof Error ? describeError(caught) : 'Could not claim this alert.');
      return;
    }

    setPhase('deciding');
    try {
      const decision = await fraudApi.decide(alert.alertId, action, trimmed);
      setPhase('done');
      // Careful wording. The decision is durable; the payment resumes asynchronously
      // via an outbox event the orchestrator consumes. Claiming the payment itself
      // finished would be a guess, and an operator who believed it would stop
      // watching for the case where it does not.
      setOutcome(
        action === 'RELEASE'
          ? `Released. Recorded as ${shortId(decision.decisionId, 8)} against your account; the payment will resume shortly.`
          : `Rejected. Recorded as ${shortId(decision.decisionId, 8)} against your account; the reserved funds will be returned shortly.`,
      );
    } catch (caught) {
      setPhase('idle');
      if (caught instanceof ApiError) {
        if (caught.isConflict) {
          setError(
            'This alert was decided by somebody else while you were typing. Nothing was changed. Refresh the queue.',
          );
          return;
        }
        if (caught.status === 400) {
          // Surfaces the server's own field message rather than a guess. The reason
          // is validated server-side and that message is the authoritative one.
          const field = caught.fieldErrors[0];
          setError(field ? `${field.field}: ${field.message}` : caught.message);
          return;
        }
        setError(describeError(caught));
        return;
      }
      setError('Could not record the decision. Nothing was changed.');
    }
  }

  const assessment = detail.state.data?.assessment ?? null;

  return (
    <div className="dialog-backdrop">
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="review-title"
      >
        <div className="dialog__head">
          <h2 id="review-title">Review alert {shortId(alert.alertId, 10)}</h2>
          <button className="button button--quiet" type="button" onClick={onClose}>
            Close
          </button>
        </div>

        {phase === 'done' && outcome ? (
          <div className="dialog__body">
            <p className="notice notice--good" role="status">
              {outcome}
            </p>
            <div className="dialog__actions">
              <button className="button button--primary" type="button" onClick={onDecided}>
                Back to the queue
              </button>
            </div>
          </div>
        ) : (
          <div className="dialog__body">
            <dl className="facts">
              <div>
                <dt>Payment</dt>
                <dd>
                  <code>{alert.paymentId}</code>
                </dd>
              </div>
              <div>
                <dt>Amount</dt>
                <dd>{formatMoney(held?.amount ?? assessment?.amount)}</dd>
              </div>
              <div>
                <dt>Initiator</dt>
                <dd>
                  <code>{alert.initiatorUserId}</code>
                </dd>
              </div>
              <div>
                <dt>Risk</dt>
                <dd>
                  <RiskBadge level={alert.riskLevel} score={alert.riskScore} />
                </dd>
              </div>
              <div>
                <dt>Engine verdict</dt>
                <dd>
                  <DecisionBadge decision={alert.decision} />{' '}
                  <DegradedBadge degraded={assessment?.degradedMode ?? held?.riskDegraded ?? false} />
                </dd>
              </div>
              <div>
                <dt>Raised</dt>
                <dd>{formatDateTime(alert.createdAt)}</dd>
              </div>
              <div>
                <dt>Funds reserved for</dt>
                <dd>{held ? formatDuration(held.waitingSeconds) : 'not held'}</dd>
              </div>
              <div>
                <dt>Model</dt>
                <dd>
                  {/*
                    Null in degraded mode: nothing was scored, so there is no model to
                    name. Printing a stale version here would misattribute a rules-only
                    verdict to the model.
                  */}
                  {assessment?.modelVersion ?? 'none — rules only'}
                </dd>
              </div>
            </dl>

            <h3 className="dialog__section">Why it was flagged</h3>
            {detail.state.status === 'loading' ? (
              <p role="status">Loading the assessment…</p>
            ) : detail.state.status === 'failed' ? (
              <LoadError
                message={`Assessment detail unavailable — ${describeError(detail.state.error)}`}
                onRetry={detail.reload}
              />
            ) : (
              <>
                <p className="dialog__summary">{alert.summary}</p>
                {assessment && assessment.reasons.length > 0 ? (
                  <ul className="reasons">
                    {assessment.reasons.map((entry) => (
                      <li key={entry}>{entry}</li>
                    ))}
                  </ul>
                ) : (
                  <p className="cell-sub">No reasons recorded.</p>
                )}
                {assessment && assessment.ruleHits.length > 0 ? (
                  <p className="rule-hits">
                    Rules fired:{' '}
                    {assessment.ruleHits.map((rule) => (
                      <code key={rule}>{rule}</code>
                    ))}
                  </p>
                ) : null}

                {detail.state.data && detail.state.data.decisions.length > 0 ? (
                  <>
                    <h3 className="dialog__section">Earlier decisions</h3>
                    <ul className="reasons">
                      {detail.state.data.decisions.map((decision) => (
                        <li key={decision.decisionId}>
                          {humanise(decision.action)} by <code>{decision.decidedBy}</code> at{' '}
                          {formatDateTime(decision.decidedAt)} — {decision.reason}
                        </li>
                      ))}
                    </ul>
                  </>
                ) : null}
              </>
            )}

            <h3 className="dialog__section">Decision</h3>
            <fieldset className="choice" disabled={phase !== 'idle'}>
              <legend className="visually-hidden">Action</legend>
              <label className="choice__option">
                <input
                  type="radio"
                  name="decision-action"
                  value="RELEASE"
                  checked={action === 'RELEASE'}
                  onChange={() => setAction('RELEASE')}
                />
                <span>
                  <strong>Release</strong>
                  <span className="choice__hint">
                    Let the payment continue. The reserved funds are captured.
                  </span>
                </span>
              </label>
              <label className="choice__option">
                <input
                  type="radio"
                  name="decision-action"
                  value="REJECT"
                  checked={action === 'REJECT'}
                  onChange={() => setAction('REJECT')}
                />
                <span>
                  <strong>Reject</strong>
                  <span className="choice__hint">
                    Refuse the payment. The reserved funds are returned to the customer.
                  </span>
                </span>
              </label>
            </fieldset>

            <label className="field" htmlFor="decision-reason">
              <span className="field__label">
                Reason <span className="field__required">required</span>
              </span>
              <textarea
                id="decision-reason"
                className="textarea"
                rows={3}
                value={reason}
                maxLength={REASON_LIMIT * 2}
                disabled={phase !== 'idle'}
                onChange={(event) => setReason(event.target.value)}
                placeholder="What you checked, and what led to this decision."
              />
            </label>
            <p className={tooLong ? 'field__counter field__counter--over' : 'field__counter'}>
              {trimmed.length} / {REASON_LIMIT}
              {/*
                The reason is mandatory server-side and this is the only record of why
                a human overrode the engine. Somebody reading it in six months — an
                auditor, or the analyst handling the customer's complaint — has nothing
                else to go on.
              */}
              {tooLong ? ' — too long, the server will refuse this' : ''}
            </p>

            {error ? (
              <p className="notice notice--bad" role="alert">
                {error}
              </p>
            ) : null}

            <div className="dialog__actions">
              <button className="button button--quiet" type="button" onClick={onClose}>
                Cancel
              </button>
              <button
                className="button button--primary"
                type="button"
                disabled={!submittable}
                onClick={() => void submit()}
              >
                {phase === 'claiming'
                  ? 'Claiming…'
                  : phase === 'deciding'
                    ? 'Recording…'
                    : action === 'REJECT'
                      ? 'Reject payment'
                      : 'Release payment'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
