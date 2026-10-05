import { useMemo, useState } from 'react';
import type { ReactElement } from 'react';
import { ApiError } from '../api/client';
import { analyticsApi, orchestrationApi } from '../api/endpoints';
import type { FailureShareView, ProviderHealthView, ProviderShareView } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { DataTable, type Column } from '../components/DataTable';
import { FreshnessNote } from '../components/FreshnessBanner';
import { KpiRow, KpiTile } from '../components/KpiTile';
import { LoadError, Loading, PageHeader, Panel } from '../components/Layout';
import { BarChart } from '../components/charts/BarChart';
import {
  DEFAULT_RANGE,
  WindowPicker,
  toBounds,
  type AnalyticsRange,
} from '../components/WindowPicker';
import { formatCount, formatDateTime, formatMillis, formatMoney, formatRate } from '../format';
import { describeError, useLoader } from '../hooks/useLoader';

/**
 * Provider health and end-to-end performance.
 *
 * Two sources, and the split is deliberate. Health, breaker state and the enable/disable
 * switch come live from the orchestrator, which owns them — a projected breaker state
 * would be a stale answer to "is this rail usable right now", which is the only reason
 * anybody opens this page in an incident. Throughput, latency percentiles and failure
 * mix come from the analytics projection, because they are historical by nature.
 *
 * Latency here is *end to end*, measured over completed payments only. Counting an
 * in-flight payment as zero would make the platform look faster the more backlog it had,
 * which is exactly backwards. Percentiles rather than a mean: one rail timing out at
 * thirty seconds barely moves an average and is the whole story in a p99.
 */
export function ProvidersPage(): ReactElement {
  const { isAdmin } = useAuth();
  const [range, setRange] = useState<AnalyticsRange>(DEFAULT_RANGE);
  const [pinnedNow, setPinnedNow] = useState(() => Date.now());
  const bounds = useMemo(() => toBounds(range, pinnedNow), [range, pinnedNow]);

  const providers = useLoader((signal) => orchestrationApi.providers(signal), [], 30_000);
  const performance = useLoader(
    (signal) => analyticsApi.performance(bounds, signal),
    [bounds.from, bounds.to, bounds.granularity],
  );

  const [pending, setPending] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [actionNote, setActionNote] = useState<string | null>(null);

  async function disable(provider: ProviderHealthView): Promise<void> {
    // A reason is required by the endpoint and it is the right requirement: this is a
    // manual override of routing, and the next person on shift needs to know why a rail
    // is off before they turn it back on.
    const reason = window.prompt(
      `Disable ${provider.displayName}? Give a reason — it is stored with the provider and shown to whoever re-enables it.`,
    );
    if (reason === null) {
      return;
    }
    if (reason.trim().length === 0) {
      setActionError('A reason is required to disable a provider.');
      return;
    }
    await run(provider.providerId, () => orchestrationApi.disableProvider(provider.providerId, reason.trim()),
      `${provider.displayName} disabled. Routing will skip it until it is enabled again.`);
  }

  async function enable(provider: ProviderHealthView): Promise<void> {
    await run(provider.providerId, () => orchestrationApi.enableProvider(provider.providerId),
      // Enabling clears the operator switch. It does not clear the circuit breaker,
      // which will keep the rail out of rotation on its own if it is still failing.
      `${provider.displayName} enabled. The circuit breaker still decides whether it is routable.`);
  }

  async function run(
    providerId: string,
    action: () => Promise<ProviderHealthView>,
    success: string,
  ): Promise<void> {
    setPending(providerId);
    setActionError(null);
    setActionNote(null);
    try {
      await action();
      setActionNote(success);
      providers.reload();
    } catch (caught) {
      if (caught instanceof ApiError && caught.isForbidden) {
        setActionError('Only an administrator can change provider routing. Nothing was changed.');
      } else if (caught instanceof Error) {
        setActionError(describeError(caught));
      } else {
        setActionError('The change could not be applied.');
      }
    } finally {
      setPending(null);
    }
  }

  const providerColumns: ReadonlyArray<Column<ProviderHealthView>> = useMemo(
    () => [
      {
        key: 'provider',
        header: 'Provider',
        render: (row) => (
          <span className="cell-stack">
            <strong>{row.displayName}</strong>
            <code className="cell-sub">{row.providerId}</code>
          </span>
        ),
      },
      {
        key: 'routable',
        header: 'Routable',
        render: (row) => (
          // The combined answer first. It is what routing actually does, and nobody
          // under pressure should have to reason across three columns to get it.
          <span className={row.routable ? 'badge badge--good' : 'badge badge--bad'}>
            {row.routable ? 'Yes' : 'No'}
          </span>
        ),
      },
      {
        key: 'enabled',
        header: 'Operator switch',
        render: (row) => (
          <span className="cell-stack">
            <span className={row.enabled ? 'badge badge--good' : 'badge badge--neutral'}>
              {row.enabled ? 'Enabled' : 'Disabled'}
            </span>
            {row.disabledReason ? <span className="cell-sub">{row.disabledReason}</span> : null}
          </span>
        ),
      },
      {
        key: 'breaker',
        header: 'Circuit breaker',
        render: (row) => (
          <span className="cell-stack">
            <span
              className={
                row.circuitBreakerState === 'CLOSED'
                  ? 'badge badge--good'
                  : row.circuitBreakerState === 'HALF_OPEN'
                    ? 'badge badge--warn'
                    : 'badge badge--bad'
              }
            >
              {row.circuitBreakerState}
            </span>
            <span className="cell-sub">
              {formatRate(row.breakerFailureRate)} failing
              {row.consecutiveFailures > 0 ? ` · ${row.consecutiveFailures} in a row` : ''}
            </span>
          </span>
        ),
      },
      {
        key: 'health',
        header: 'Last probe',
        secondary: true,
        render: (row) => (
          <span className="cell-stack">
            <span>{row.observedHealth}</span>
            <span className="cell-sub">
              {formatMillis(row.lastLatencyMs)}
              {row.lastCheckedAt ? ` · ${formatDateTime(row.lastCheckedAt)}` : ''}
            </span>
          </span>
        ),
      },
      {
        key: 'limits',
        header: 'Limits',
        secondary: true,
        render: (row) => (
          <span className="cell-stack">
            <span>{row.feeBasisPoints} bps</span>
            <span className="cell-sub">
              max {formatMoney({ amount: '', amountMinor: row.maxAmountMinor, currency: row.supportedCurrencies[0] ?? 'INR' })}
              {' · '}
              {row.supportedCurrencies.join(', ')}
            </span>
          </span>
        ),
      },
      {
        key: 'actions',
        header: 'Action',
        render: (row) => (
          <button
            className="button button--quiet"
            type="button"
            // Disabled rather than hidden for a non-admin, with the reason in the
            // tooltip. A button that vanishes leaves an analyst wondering whether the
            // capability exists; one that explains itself does not.
            disabled={!isAdmin || pending === row.providerId}
            title={isAdmin ? undefined : 'Requires the ADMIN role'}
            onClick={() => void (row.enabled ? disable(row) : enable(row))}
          >
            {pending === row.providerId ? 'Working…' : row.enabled ? 'Disable' : 'Enable'}
          </button>
        ),
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [isAdmin, pending],
  );

  const shareColumns: ReadonlyArray<Column<ProviderShareView>> = useMemo(
    () => [
      { key: 'provider', header: 'Provider', render: (row) => <code>{row.providerId}</code> },
      { key: 'payments', header: 'Payments', numeric: true, render: (row) => formatCount(row.payments) },
      {
        key: 'completed',
        header: 'Completed',
        numeric: true,
        render: (row) => formatCount(row.completed),
      },
      {
        key: 'fellBack',
        header: 'Fell back',
        numeric: true,
        render: (row) => formatCount(row.fellBack),
      },
      {
        key: 'latency',
        header: 'Mean latency',
        numeric: true,
        render: (row) => formatMillis(row.averageLatencyMs),
      },
      { key: 'settled', header: 'Settled', numeric: true, render: (row) => formatMoney(row.settled) },
    ],
    [],
  );

  const failureColumns: ReadonlyArray<Column<FailureShareView>> = useMemo(
    () => [
      {
        key: 'code',
        header: 'Failure code',
        render: (row) => <code>{row.failureCode}</code>,
      },
      { key: 'payments', header: 'Payments', numeric: true, render: (row) => formatCount(row.payments) },
    ],
    [],
  );

  const perf = performance.state.data;

  return (
    <div className="page">
      <PageHeader
        title="Providers and performance"
        description="Rail health live from the orchestrator; throughput and latency from the analytics projection."
      >
        <WindowPicker
          value={range}
          onChange={(next) => {
            setRange(next);
            setPinnedNow(Date.now());
          }}
          onRefresh={() => {
            setPinnedNow(Date.now());
            providers.reload();
          }}
          refreshing={performance.refreshing || providers.refreshing}
          {...(perf ? { note: <FreshnessNote freshness={perf.freshness} /> } : {})}
        />
      </PageHeader>

      {actionNote ? (
        <p className="notice notice--good" role="status">
          {actionNote}
        </p>
      ) : null}
      {actionError ? (
        <p className="notice notice--bad" role="alert">
          {actionError}
        </p>
      ) : null}

      <Panel
        title="Rails"
        description="Routable is the combined answer: the operator switch and the circuit breaker both have to allow it."
      >
        <DataTable
          caption="Payment provider health and routing state"
          columns={providerColumns}
          rows={providers.state.data ?? []}
          rowKey={(row) => row.providerId}
          loading={providers.state.status === 'loading'}
          error={
            providers.state.status === 'failed' && !providers.state.data
              ? describeError(providers.state.error)
              : null
          }
          onRetry={providers.reload}
          empty="No providers are configured."
        />
        {!isAdmin ? (
          <p className="panel__note">
            You can see routing state but not change it. Enabling or disabling a rail needs the{' '}
            <code>ADMIN</code> role.
          </p>
        ) : null}
      </Panel>

      <Panel
        title="End-to-end latency"
        description="Completed payments only, from creation to settlement. Percentiles, because a mean hides the tail that customers actually notice."
      >
        {performance.state.status === 'loading' ? <Loading what="performance" /> : null}
        {performance.state.status === 'failed' && !perf ? (
          <LoadError message={describeError(performance.state.error)} onRetry={performance.reload} />
        ) : null}

        {perf ? (
          <>
            <KpiRow>
              <KpiTile
                label="Samples"
                value={formatCount(perf.latency.samples)}
                // Read first, deliberately. A p99 over four completed payments is not a
                // p99, and a tile that shows one without its sample count invites a
                // decision based on noise.
                hint="Completed payments in this window. A percentile over a handful of samples is not meaningful."
              />
              <KpiTile label="p50" value={formatMillis(perf.latency.p50)} hint="Median." />
              <KpiTile
                label="p95"
                value={formatMillis(perf.latency.p95)}
                tone={perf.latency.p95 > 10_000 ? 'warn' : 'neutral'}
              />
              <KpiTile
                label="p99"
                value={formatMillis(perf.latency.p99)}
                tone={perf.latency.p99 > 30_000 ? 'bad' : 'neutral'}
              />
              <KpiTile label="Worst" value={formatMillis(perf.latency.worst)} />
            </KpiRow>

            {perf.providers.length > 0 ? (
              <BarChart
                caption="Payments by provider, completed against fallbacks"
                labels={perf.providers.map((row) => row.providerId)}
                formatValue={formatCount}
                series={[
                  {
                    id: 'completed',
                    label: 'Completed',
                    colour: '#2f9e6b',
                    values: perf.providers.map((row) => row.completed),
                  },
                  {
                    id: 'fellBack',
                    label: 'Fell back to another rail',
                    colour: '#e08a1e',
                    values: perf.providers.map((row) => row.fellBack),
                  },
                ]}
                emptyMessage="No provider attempts in this window."
              />
            ) : null}

            <DataTable
              caption="Throughput by provider"
              columns={shareColumns}
              rows={perf.providers}
              rowKey={(row) => row.providerId}
              empty="No payments reached a provider in this window."
            />
          </>
        ) : null}
      </Panel>

      <Panel
        title="Failure mix"
        description="Why payments failed after being accepted. A code climbing the list is usually one rail degrading."
      >
        <DataTable
          caption="Failure codes by frequency"
          columns={failureColumns}
          rows={perf?.failures ?? []}
          rowKey={(row) => row.failureCode}
          loading={performance.state.status === 'loading'}
          empty="No failures in this window."
        />
        {perf && perf.failures.length === 0 ? (
          <p className="panel__note">
            Nothing failed in this window. Rejections on risk are not failures and are counted
            separately, on the overview.
          </p>
        ) : null}
      </Panel>

      {/*
        Said plainly because somebody will eventually notice a discrepancy and need to
        know which number to trust. The projection derives its money totals from events;
        the ledger is the book of record and wins by definition.
      */}
      <p className="page__footnote">
        Settled and fee totals here are derived from payment events. The ledger is the book of
        record — where the two disagree, the ledger is right.
      </p>
    </div>
  );
}
