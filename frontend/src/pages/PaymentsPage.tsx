import { useMemo, useState } from 'react';
import type { ReactElement } from 'react';
import { analyticsApi } from '../api/endpoints';
import type { PaymentFactView, PaymentStatus, RiskLevel } from '../api/types';
import { DataTable, Pager, type Column } from '../components/DataTable';
import { FreshnessNote } from '../components/FreshnessBanner';
import { PageHeader, Panel } from '../components/Layout';
import { DegradedBadge, PaymentStatusBadge, RiskBadge } from '../components/StatusBadge';
import {
  ABSENT,
  formatDateTime,
  formatMillis,
  formatMoney,
  humanise,
  shortId,
} from '../format';
import { describeError, useLoader } from '../hooks/useLoader';

const PAGE_SIZE = 25;

const STATUSES: ReadonlyArray<PaymentStatus> = [
  'CREATED',
  'FUNDS_RESERVED',
  'RISK_ASSESSED',
  'ON_HOLD',
  'AUTHORIZED',
  'CAPTURED',
  'COMPLETED',
  'REJECTED',
  'FAILED',
  'REFUNDED',
  'PARTIALLY_REFUNDED',
];

const RISK_LEVELS: ReadonlyArray<RiskLevel> = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

/**
 * Converts a typed amount into exact minor units for the server's BIGINT comparison.
 *
 * <p>Parsed as two integer parts rather than `Number(value) * 100`. `19.99 * 100` is
 * 1998.9999999999998 in binary floating point, and `Math.round` would rescue this
 * particular case while leaving the general one to fail quietly on some other value.
 * The whole platform stores money as integers for this reason; a filter is not an
 * excuse to stop.
 *
 * Returns null for an empty or unparseable entry, which the caller treats as no filter.
 */
function toMinorUnits(value: string, exponent = 2): number | null {
  const trimmed = value.trim();
  if (trimmed === '') {
    return null;
  }
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(trimmed);
  if (!match) {
    return null;
  }
  const whole = match[1] ?? '0';
  const fraction = (match[2] ?? '').padEnd(exponent, '0').slice(0, exponent);
  const minor = Number(`${whole}${fraction}`);
  return Number.isSafeInteger(minor) ? minor : null;
}

/**
 * Every payment on the platform, across all customers.
 *
 * This is the one read no other service offers. payment-service scopes its queries to
 * the calling customer by design — a cross-customer listing there would be one missing
 * predicate away from letting a customer page through everybody else's payments. So the
 * capability lives here instead, behind an endpoint that is admin-only from the gateway
 * inward and is served from a projection rather than from the transactional tables.
 *
 * Two fields the backend deliberately leaves out of this projection, and they are worth
 * knowing about: the payment `description`, which is customer free text, and the risk
 * signal snapshot, which contains device and location data the customer never knowingly
 * handed to a person. Staff read this list across every customer on the platform, and
 * the smallest useful set of columns is the right one.
 */
export function PaymentsPage(): ReactElement {
  const [status, setStatus] = useState<PaymentStatus | ''>('');
  const [riskLevel, setRiskLevel] = useState<RiskLevel | ''>('');
  const [userId, setUserId] = useState('');
  const [providerId, setProviderId] = useState('');
  const [degradedOnly, setDegradedOnly] = useState(false);
  const [minRiskScore, setMinRiskScore] = useState('');
  const [minAmount, setMinAmount] = useState('');
  const [page, setPage] = useState(0);

  /** Applied filters, separate from the inputs so typing does not fire a request per keystroke. */
  const [applied, setApplied] = useState({
    userId: '',
    providerId: '',
    minRiskScore: '',
    minAmount: '',
  });

  const payments = useLoader(
    (signal) =>
      analyticsApi.payments(
        {
          ...(status === '' ? {} : { status }),
          ...(riskLevel === '' ? {} : { riskLevel }),
          ...(applied.userId.trim() === '' ? {} : { userId: applied.userId.trim() }),
          ...(applied.providerId.trim() === '' ? {} : { providerId: applied.providerId.trim() }),
          // Sent only when on. `degradedOnly=false` and an absent filter mean the same
          // thing to this endpoint, so there is nothing gained by sending it.
          ...(degradedOnly ? { degradedOnly: true } : {}),
          ...(applied.minRiskScore === '' ? {} : { minRiskScore: Number(applied.minRiskScore) }),
          ...(toMinorUnits(applied.minAmount) === null
            ? {}
            : { minAmountMinor: toMinorUnits(applied.minAmount) as number }),
          page,
          size: PAGE_SIZE,
        },
        signal,
      ),
    [
      status,
      riskLevel,
      applied.userId,
      applied.providerId,
      applied.minRiskScore,
      applied.minAmount,
      degradedOnly,
      page,
    ],
  );

  const projection = useLoader((signal) => analyticsApi.projection(signal), [], 60_000);

  const columns: ReadonlyArray<Column<PaymentFactView>> = useMemo(
    () => [
      {
        key: 'payment',
        header: 'Payment',
        render: (row) => (
          <span className="cell-stack">
            <code title={row.paymentId}>{shortId(row.paymentId, 10)}</code>
            <span className="cell-sub">{humanise(row.paymentType)}</span>
          </span>
        ),
      },
      {
        key: 'amount',
        header: 'Amount',
        numeric: true,
        render: (row) => (
          <span className="cell-stack">
            <span>{formatMoney(row.amount)}</span>
            {row.refunded && row.refunded.amountMinor > 0 ? (
              <span className="cell-sub">refunded {formatMoney(row.refunded)}</span>
            ) : null}
          </span>
        ),
      },
      {
        key: 'status',
        header: 'Status',
        render: (row) => <PaymentStatusBadge status={row.status} />,
      },
      {
        key: 'risk',
        header: 'Risk',
        render: (row) => (
          <span className="cell-stack">
            <RiskBadge level={row.riskLevel} score={row.riskScore} />
            <DegradedBadge degraded={row.degradedMode} />
          </span>
        ),
      },
      {
        key: 'initiator',
        header: 'Initiator',
        secondary: true,
        render: (row) =>
          row.initiatorUserId ? (
            <button
              className="link-button"
              type="button"
              // Filtering by the user is the action somebody always wants next after
              // spotting one suspicious payment.
              onClick={() => {
                setUserId(row.initiatorUserId ?? '');
                setApplied((current) => ({ ...current, userId: row.initiatorUserId ?? '' }));
                setPage(0);
              }}
              title={row.initiatorUserId}
            >
              {shortId(row.initiatorUserId, 10)}
            </button>
          ) : (
            ABSENT
          ),
      },
      {
        key: 'provider',
        header: 'Provider',
        secondary: true,
        render: (row) => (
          <span className="cell-stack">
            <span>{row.providerId ?? ABSENT}</span>
            {/* More than one attempt means the payment fell back to another rail.
                It succeeded, but the first rail did not. */}
            {row.providerAttempts !== null && row.providerAttempts > 1 ? (
              <span className="cell-sub">{row.providerAttempts} attempts</span>
            ) : null}
          </span>
        ),
      },
      {
        key: 'latency',
        header: 'End to end',
        numeric: true,
        secondary: true,
        // Only completed payments carry a latency. An in-flight one shown as 0 would
        // make the platform look faster the more backlog it had.
        render: (row) => formatMillis(row.endToEndLatencyMs),
      },
      {
        key: 'outcome',
        header: 'Outcome',
        render: (row) =>
          row.failureCode ? (
            <span className="cell-stack">
              <code className="cell-failure">{row.failureCode}</code>
              {row.failureReason ? <span className="cell-sub">{row.failureReason}</span> : null}
            </span>
          ) : row.ledgerTransactionId ? (
            <span className="cell-sub" title={row.ledgerTransactionId}>
              posted {shortId(row.ledgerTransactionId, 8)}
            </span>
          ) : (
            <span className="cell-sub">{ABSENT}</span>
          ),
      },
      {
        key: 'created',
        header: 'Created',
        render: (row) => (
          <span className="cell-stack">
            <span>{formatDateTime(row.createdAt)}</span>
            {row.completedAt ? (
              <span className="cell-sub">finished {formatDateTime(row.completedAt)}</span>
            ) : null}
          </span>
        ),
      },
    ],
    [],
  );

  const data = payments.state.data;

  function applyTextFilters(): void {
    setApplied({ userId, providerId, minRiskScore, minAmount });
    setPage(0);
  }

  const amountIsUnparseable = minAmount.trim() !== '' && toMinorUnits(minAmount) === null;

  return (
    <div className="page">
      <PageHeader
        title="Payments"
        description="Every payment on the platform, from the analytics projection. Customer free text and device signals are deliberately not included."
      >
        {/*
          The lag belongs next to the list, not only on the overview. A payment
          created thirty seconds ago will not be here yet, and an operator searching
          for one they were just told about needs to know that before concluding it
          does not exist.
        */}
        {projection.state.data ? (
          <FreshnessNote freshness={projection.state.data.freshness} />
        ) : null}
      </PageHeader>

      <Panel
        title="Filters"
        description="Filters are applied by the server; only the matching page is transferred."
      >
        <div className="filters filters--wrap">
          <label className="field field--inline" htmlFor="filter-status">
            <span className="field__label">Status</span>
            <select
              id="filter-status"
              className="select"
              value={status}
              onChange={(event) => {
                setStatus(event.target.value as PaymentStatus | '');
                setPage(0);
              }}
            >
              <option value="">Any</option>
              {STATUSES.map((value) => (
                <option key={value} value={value}>
                  {humanise(value)}
                </option>
              ))}
            </select>
          </label>

          <label className="field field--inline" htmlFor="filter-risk">
            <span className="field__label">Risk level</span>
            <select
              id="filter-risk"
              className="select"
              value={riskLevel}
              onChange={(event) => {
                setRiskLevel(event.target.value as RiskLevel | '');
                setPage(0);
              }}
            >
              <option value="">Any</option>
              {RISK_LEVELS.map((value) => (
                <option key={value} value={value}>
                  {humanise(value)}
                </option>
              ))}
            </select>
          </label>

          <label className="field field--inline" htmlFor="filter-user">
            <span className="field__label">Initiator user id</span>
            <input
              id="filter-user"
              className="input"
              value={userId}
              onChange={(event) => setUserId(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  applyTextFilters();
                }
              }}
              placeholder="exact id"
            />
          </label>

          <label className="field field--inline" htmlFor="filter-provider">
            <span className="field__label">Provider</span>
            <input
              id="filter-provider"
              className="input"
              value={providerId}
              onChange={(event) => setProviderId(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  applyTextFilters();
                }
              }}
              placeholder="e.g. SWIFTPAY"
            />
          </label>

          <label className="field field--inline" htmlFor="filter-score">
            <span className="field__label">Min risk score</span>
            <input
              id="filter-score"
              className="input input--narrow"
              type="number"
              min={0}
              max={100}
              value={minRiskScore}
              onChange={(event) => setMinRiskScore(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  applyTextFilters();
                }
              }}
              placeholder="0-100"
            />
          </label>

          <label className="field field--inline" htmlFor="filter-amount">
            <span className="field__label">Min amount</span>
            <input
              id="filter-amount"
              className="input input--narrow"
              // Text, not number. A number input lets the browser localise the decimal
              // separator and offer a spinner over a currency, and the value is parsed
              // into exact minor units here rather than handed to floating point.
              inputMode="decimal"
              value={minAmount}
              onChange={(event) => setMinAmount(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  applyTextFilters();
                }
              }}
              placeholder="10000.00"
            />
          </label>

          <label className="checkbox">
            <input
              type="checkbox"
              checked={degradedOnly}
              onChange={(event) => {
                setDegradedOnly(event.target.checked);
                setPage(0);
              }}
            />
            Degraded verdicts only
          </label>

          <button
            className="button button--primary"
            type="button"
            // Refused rather than sent. An unparseable amount would drop out of the query
            // silently, and the operator would read a list of every payment as a list of
            // payments over their threshold.
            disabled={amountIsUnparseable}
            onClick={applyTextFilters}
          >
            Apply
          </button>
          <button
            className="button button--quiet"
            type="button"
            onClick={() => {
              setStatus('');
              setRiskLevel('');
              setUserId('');
              setProviderId('');
              setMinRiskScore('');
              setMinAmount('');
              setDegradedOnly(false);
              setApplied({ userId: '', providerId: '', minRiskScore: '', minAmount: '' });
              setPage(0);
            }}
          >
            Clear
          </button>
        </div>

        {amountIsUnparseable ? (
          <p className="notice notice--bad" role="alert">
            Enter the minimum amount as a plain number, with at most two decimal places.
          </p>
        ) : null}
      </Panel>

      <Panel
        title="Results"
        description={
          data
            ? `${data.totalElements.toLocaleString('en-US')} payments match.`
            : 'Loading the payment list.'
        }
        actions={
          <button className="button button--quiet" type="button" onClick={payments.reload}>
            {payments.refreshing ? 'Refreshing…' : 'Refresh'}
          </button>
        }
      >
        <DataTable
          caption="Payments across all customers"
          columns={columns}
          rows={data?.content ?? []}
          rowKey={(row) => row.paymentId}
          loading={payments.state.status === 'loading'}
          error={
            payments.state.status === 'failed' && !data ? describeError(payments.state.error) : null
          }
          onRetry={payments.reload}
          empty="No payments match these filters."
        />

        {data ? (
          <Pager
            page={data.page}
            size={data.size}
            totalElements={data.totalElements}
            hasNext={!data.last}
            onPage={setPage}
            busy={payments.refreshing}
          />
        ) : null}
      </Panel>
    </div>
  );
}
