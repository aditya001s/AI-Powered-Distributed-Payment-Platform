import { useCallback, useMemo, useState } from 'react';
import type { ReactElement } from 'react';
import { Link } from 'react-router-dom';
import { analyticsApi, fraudApi, orchestrationApi } from '../api/endpoints';
import { FreshnessBanner } from '../components/FreshnessBanner';
import { KpiRow, KpiTile } from '../components/KpiTile';
import { LoadError, Loading, PageHeader, Panel } from '../components/Layout';
import { BarChart } from '../components/charts/BarChart';
import { LineChart } from '../components/charts/LineChart';
import {
  DEFAULT_RANGE,
  WindowPicker,
  toBounds,
  type AnalyticsRange,
} from '../components/WindowPicker';
import {
  formatCount,
  formatDayAndTime,
  formatMoney,
  formatMoneyCompact,
  formatRate,
  formatTimeOfDay,
} from '../format';
import { describeError, useLoader } from '../hooks/useLoader';

/**
 * The landing page: what the platform did in a window, and what needs attention now.
 *
 * Two kinds of number sit here and they come from different places on purpose.
 *
 * The *historical* figures — payments, settled value, success rate, throughput over
 * time — come from the analytics read model, which derives them from a projection and
 * reports how far behind it is.
 *
 * The *operational* figures — payments held for review, uncompensated sagas, blocking
 * alerts — come from the services that own that state, live. They are the numbers
 * somebody acts on within the minute, and a projection lagging by two minutes is long
 * enough for an operator to release a payment that was already released.
 */
export function OverviewPage(): ReactElement {
  const [range, setRange] = useState<AnalyticsRange>(DEFAULT_RANGE);

  /*
   * The window is pinned when the page loads, not recomputed per request.
   *
   * With a live `Date.now()` in each fetcher, the overview and the time series would
   * cover windows a few milliseconds apart — enough for a payment to land in one and
   * not the other, leaving a headline total that disagrees with the chart under it.
   */
  const [pinnedNow, setPinnedNow] = useState(() => Date.now());
  const bounds = useMemo(() => toBounds(range, pinnedNow), [range, pinnedNow]);

  const overview = useLoader(
    (signal) => analyticsApi.overview(bounds, signal),
    [bounds.from, bounds.to, bounds.granularity],
  );
  const series = useLoader(
    (signal) => analyticsApi.timeseries(bounds, signal),
    [bounds.from, bounds.to, bounds.granularity],
  );

  // Live operational state, refreshed on its own schedule. Thirty seconds is a
  // compromise: often enough that a queue does not go unnoticed, rarely enough that
  // six open dashboards do not become meaningful load on the fraud service.
  const fraud = useLoader((signal) => fraudApi.stats(signal), [], 30_000);
  const orchestration = useLoader((signal) => orchestrationApi.stats(signal), [], 30_000);

  const reloadAll = useCallback(() => {
    setPinnedNow(Date.now());
    fraud.reload();
    orchestration.reload();
  }, [fraud, orchestration]);

  const overviewData = overview.state.data;
  const seriesData = series.state.data;

  return (
    <div className="page">
      <PageHeader
        title="Overview"
        description="Platform activity for the selected window, alongside what currently needs a decision."
      >
        <WindowPicker
          value={range}
          onChange={(next) => {
            setRange(next);
            setPinnedNow(Date.now());
          }}
          onRefresh={reloadAll}
          refreshing={overview.refreshing || series.refreshing}
        />
      </PageHeader>

      {overviewData ? <FreshnessBanner freshness={overviewData.freshness} /> : null}

      <Panel
        title="Needs attention"
        description="Read live from the fraud and orchestration services, not from the projection — these are acted on within minutes."
      >
        {fraud.state.status === 'failed' && !fraud.state.data ? (
          <LoadError message={describeError(fraud.state.error)} onRetry={fraud.reload} />
        ) : null}

        <KpiRow>
          <KpiTile
            label="Blocking alerts"
            value={formatCount(fraud.state.data?.blockingAlerts)}
            tone={(fraud.state.data?.blockingAlerts ?? 0) > 0 ? 'warn' : 'neutral'}
            hint="Each one is a customer whose funds are reserved pending a human decision."
            emphasis={<Link to="/alerts">Work the queue →</Link>}
          />
          <KpiTile
            label="Open alerts"
            value={formatCount(fraud.state.data?.openAlerts)}
            hint="Raised and not yet decided, including non-blocking ones."
          />
          <KpiTile
            label="Held over an hour"
            value={formatCount(orchestration.state.data?.heldOverAnHour)}
            // Non-zero here means either nobody is working the queue or the decision
            // consumer has stopped. Both are worth interrupting somebody for.
            tone={(orchestration.state.data?.heldOverAnHour ?? 0) > 0 ? 'bad' : 'neutral'}
            hint="A payment held this long means the queue is not being worked, or decisions are not being applied."
          />
          <KpiTile
            label="Uncompensated"
            value={formatCount(orchestration.state.data?.uncompensated)}
            // Expected to be exactly zero. Anything else is money that may be
            // stranded between a reservation and a posting.
            tone={(orchestration.state.data?.uncompensated ?? 0) > 0 ? 'bad' : 'good'}
            hint="Expected to be zero. Each one is a saga that failed without unwinding its reservation."
          />
          <KpiTile
            label="In flight"
            value={formatCount(orchestration.state.data?.inFlight)}
            hint="Sagas currently running."
          />
        </KpiRow>
      </Panel>

      <Panel
        title="Activity"
        description="Derived from the payment projection. Every total below is recomputed from one row per payment, so a redelivered event cannot inflate it."
      >
        {overview.state.status === 'loading' ? <Loading what="totals" /> : null}
        {overview.state.status === 'failed' && !overviewData ? (
          <LoadError message={describeError(overview.state.error)} onRetry={overview.reload} />
        ) : null}

        {overviewData ? (
          <>
            <KpiRow>
              <KpiTile
                label="Payments"
                value={formatCount(overviewData.payments)}
                hint="Payments created in this window."
              />
              <KpiTile
                label="Completed"
                value={formatCount(overviewData.completed)}
                tone="good"
                emphasis={formatMoney(overviewData.settled)}
                hint="Settled value counts completed payments only."
              />
              <KpiTile
                label="Success rate"
                // Null, not zero, when nothing reached a terminal state. A zero rate
                // says every payment failed; null says none have finished yet, and on
                // a quiet morning those look identical while meaning opposites.
                value={formatRate(overviewData.successRate)}
                tone={
                  overviewData.successRate === null
                    ? 'neutral'
                    : overviewData.successRate >= 0.95
                      ? 'good'
                      : overviewData.successRate >= 0.85
                        ? 'warn'
                        : 'bad'
                }
                hint={
                  overviewData.successRate === null
                    ? 'Nothing reached a terminal state in this window — which is not a zero rate.'
                    : 'Completed as a share of payments that reached a terminal state.'
                }
              />
              <KpiTile
                label="Failed"
                value={formatCount(overviewData.failed)}
                tone={overviewData.failed > 0 ? 'warn' : 'neutral'}
                hint="Failed after being accepted, as opposed to refused on risk."
              />
              <KpiTile
                label="Rejected"
                value={formatCount(overviewData.rejected)}
                hint="Refused, mostly on risk. Not a platform fault."
              />
              <KpiTile
                label="On hold"
                value={formatCount(overviewData.onHold)}
                tone={overviewData.onHold > 0 ? 'warn' : 'neutral'}
                hint="Projected count. The live figure is above."
              />
            </KpiRow>

            <KpiRow>
              <KpiTile
                label="High risk"
                value={formatCount(overviewData.highRisk)}
                hint="Scored HIGH or CRITICAL."
              />
              <KpiTile
                label="Degraded verdicts"
                value={formatCount(overviewData.degradedVerdicts)}
                tone={overviewData.degradedVerdicts > 0 ? 'warn' : 'neutral'}
                // The platform is configured not to reject when the model is
                // unavailable, so these went through on rules alone. That is a
                // deliberate availability choice, and it needs to be visible.
                hint="Assessed without the ML model, on rules alone. Not the same as low risk."
              />
              <KpiTile
                label="Provider fallbacks"
                value={formatCount(overviewData.fellBack)}
                hint="Payments that succeeded only after switching rail."
              />
              <KpiTile
                label="Refunded"
                value={formatCount(overviewData.refunded)}
                emphasis={formatMoney(overviewData.refundedAmount)}
                hint="Full and partial refunds."
              />
              <KpiTile
                label="Fees"
                value={formatMoney(overviewData.fees)}
                hint="Provider fees on completed payments."
              />
              <KpiTile
                label="In flight (projected)"
                value={formatCount(overviewData.inFlight)}
                hint="Created but not yet terminal, as at the projection's position."
              />
            </KpiRow>
          </>
        ) : null}
      </Panel>

      <Panel
        title="Throughput"
        description="Payments created against payments completed. A widening gap is a backlog forming."
      >
        {series.state.status === 'loading' ? <Loading what="time series" /> : null}
        {series.state.status === 'failed' && !seriesData ? (
          <LoadError message={describeError(series.state.error)} onRetry={series.reload} />
        ) : null}

        {seriesData ? (
          <>
            <LineChart
              caption={`Payments per ${seriesData.window.granularity}`}
              labels={seriesData.buckets.map((bucket) => bucket.bucketStart)}
              formatLabel={range.seconds <= 86_400 ? formatTimeOfDay : formatDayAndTime}
              formatValue={formatCount}
              series={[
                {
                  id: 'payments',
                  label: 'Created',
                  colour: '#4c7ef3',
                  values: seriesData.buckets.map((bucket) => bucket.payments),
                  fill: true,
                },
                {
                  id: 'completed',
                  label: 'Completed',
                  colour: '#2f9e6b',
                  values: seriesData.buckets.map((bucket) => bucket.completed),
                },
                {
                  id: 'unsuccessful',
                  label: 'Failed or rejected',
                  colour: '#d1495b',
                  values: seriesData.buckets.map((bucket) => bucket.unsuccessful),
                },
              ]}
            />

            <BarChart
              caption={`Settled value per ${seriesData.window.granularity}`}
              labels={seriesData.buckets.map((bucket) =>
                range.seconds <= 86_400
                  ? formatTimeOfDay(bucket.bucketStart)
                  : formatDayAndTime(bucket.bucketStart),
              )}
              // Minor units on the axis, formatted back into currency for the label.
              // Charting the decimal string would mean parsing a float out of money,
              // which is the one thing this platform never does.
              formatValue={(value) =>
                formatMoneyCompact({
                  amount: '',
                  amountMinor: value,
                  currency: seriesData.buckets[0]?.settled.currency ?? 'INR',
                })
              }
              series={[
                {
                  id: 'settled',
                  label: 'Settled',
                  colour: '#2f9e6b',
                  values: seriesData.buckets.map((bucket) => bucket.settled.amountMinor),
                },
              ]}
              emptyMessage="No settled value in this window."
            />

            <LineChart
              caption={`Risk per ${seriesData.window.granularity}`}
              labels={seriesData.buckets.map((bucket) => bucket.bucketStart)}
              formatLabel={range.seconds <= 86_400 ? formatTimeOfDay : formatDayAndTime}
              formatValue={formatCount}
              series={[
                {
                  id: 'highRisk',
                  label: 'High risk',
                  colour: '#e08a1e',
                  values: seriesData.buckets.map((bucket) => bucket.highRisk),
                },
                {
                  id: 'degraded',
                  label: 'Degraded verdicts',
                  colour: '#8b6cd1',
                  values: seriesData.buckets.map((bucket) => bucket.degradedVerdicts),
                },
              ]}
            />
          </>
        ) : null}
      </Panel>
    </div>
  );
}
