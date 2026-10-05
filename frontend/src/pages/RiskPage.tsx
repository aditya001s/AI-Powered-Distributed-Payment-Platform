import { useMemo, useState } from 'react';
import type { ReactElement } from 'react';
import { analyticsApi, fraudApi } from '../api/endpoints';
import type { RiskBandView } from '../api/types';
import { DataTable, type Column } from '../components/DataTable';
import { FreshnessBanner } from '../components/FreshnessBanner';
import { KpiRow, KpiTile } from '../components/KpiTile';
import { LoadError, Loading, PageHeader, Panel } from '../components/Layout';
import { RiskBadge } from '../components/StatusBadge';
import { BarChart } from '../components/charts/BarChart';
import {
  DEFAULT_RANGE,
  WindowPicker,
  toBounds,
  type AnalyticsRange,
} from '../components/WindowPicker';
import { formatCount, formatMillis, formatMoney, formatRate, humanise } from '../format';
import { describeError, useLoader } from '../hooks/useLoader';

/** Colours for the two halves of every stacked bar. Each is labelled as well as coloured. */
const COMPLETED = '#2f9e6b';
const STOPPED = '#d1495b';
const HELD = '#e08a1e';

/**
 * Risk visualisation: where the scores fall, and what the platform did about them.
 *
 * The chart that matters is the stacked one. "How many HIGH-risk payments were there"
 * is not a useful question on its own — the useful one is "of the HIGH-risk payments,
 * how many completed anyway", because that is the number that says whether the
 * thresholds are set where somebody thinks they are. A stacked bar answers both at once.
 *
 * Two things are kept visible throughout rather than tucked into a footnote:
 *
 * `UNSCORED` is a band of its own. Those payments were refused before the engine ever
 * scored them, and folding them into LOW would both overstate how many genuinely
 * low-risk payments there were and make the band counts stop adding up to the total.
 *
 * Degraded verdicts are counted separately. The platform is configured not to reject
 * when the model is unavailable, so those payments were assessed on rules alone. A LOW
 * band full of them does not mean "low risk"; it means "nothing was found by the half
 * of the system that was working".
 */
export function RiskPage(): ReactElement {
  const [range, setRange] = useState<AnalyticsRange>(DEFAULT_RANGE);
  const [pinnedNow, setPinnedNow] = useState(() => Date.now());
  const bounds = useMemo(() => toBounds(range, pinnedNow), [range, pinnedNow]);

  const risk = useLoader(
    (signal) => analyticsApi.risk(bounds, signal),
    [bounds.from, bounds.to, bounds.granularity],
  );

  /*
   * The engine's own configuration, read live from fraud-service.
   *
   * Without the thresholds the histogram is a shape with no meaning: a cluster at 65
   * is either just under the review line or well over it, and only fraud-service
   * knows which. Reproducing the thresholds in this UI would let them drift.
   */
  const engine = useLoader((signal) => fraudApi.stats(signal), [], 60_000);

  const data = risk.state.data;

  const bandColumns: ReadonlyArray<Column<RiskBandView>> = useMemo(
    () => [
      {
        key: 'band',
        header: 'Band',
        render: (row) => <RiskBadge level={row.riskLevel} />,
      },
      { key: 'payments', header: 'Payments', numeric: true, render: (row) => formatCount(row.payments) },
      {
        key: 'completed',
        header: 'Completed',
        numeric: true,
        render: (row) => formatCount(row.completed),
      },
      {
        key: 'completionRate',
        header: 'Completed share',
        numeric: true,
        // Computed here rather than server-side because it is a ratio of two figures
        // already on the row, and a rate the server also sent could disagree with them
        // after a rounding difference.
        render: (row) =>
          row.payments === 0 ? '—' : formatRate(row.completed / row.payments, 0),
      },
      { key: 'onHold', header: 'On hold', numeric: true, render: (row) => formatCount(row.onHold) },
      {
        key: 'rejected',
        header: 'Rejected',
        numeric: true,
        render: (row) => formatCount(row.rejected),
      },
      {
        key: 'degraded',
        header: 'Degraded',
        numeric: true,
        secondary: true,
        render: (row) => formatCount(row.degradedVerdicts),
      },
      {
        key: 'amount',
        header: 'Value',
        numeric: true,
        render: (row) => formatMoney(row.amount),
      },
    ],
    [],
  );

  return (
    <div className="page">
      <PageHeader
        title="Risk"
        description="How the fraud engine scored the platform's traffic, and what happened to each band."
      >
        <WindowPicker
          value={range}
          onChange={(next) => {
            setRange(next);
            setPinnedNow(Date.now());
          }}
          onRefresh={() => setPinnedNow(Date.now())}
          refreshing={risk.refreshing}
        />
      </PageHeader>

      {data ? <FreshnessBanner freshness={data.freshness} /> : null}

      {risk.state.status === 'loading' ? <Loading what="risk distribution" /> : null}
      {risk.state.status === 'failed' && !data ? (
        <LoadError message={describeError(risk.state.error)} onRetry={risk.reload} />
      ) : null}

      {data ? (
        <>
          <KpiRow>
            <KpiTile
              label="Degraded verdicts"
              value={formatCount(data.degradedVerdicts)}
              tone={data.degradedVerdicts > 0 ? 'warn' : 'neutral'}
              hint="Assessed without the ML model. Rules only — not the same as low risk."
            />
            <KpiTile
              label="Unscored"
              value={formatCount(data.unscored)}
              tone={data.unscored > 0 ? 'warn' : 'neutral'}
              hint="Refused before the engine scored them. Counted apart from LOW on purpose."
            />
            <KpiTile
              label="Open alerts"
              value={formatCount(engine.state.data?.openAlerts)}
              hint="Live from the fraud service, not from the projection."
            />
            <KpiTile
              label="Scoring latency"
              value={formatMillis(engine.state.data?.averageScoringLatencyMs)}
              hint="Mean time the engine took to reach a verdict."
            />
            <KpiTile
              label="Holds (24h)"
              value={formatCount(engine.state.data?.holds24h)}
              hint="Verdicts that stopped a payment pending review."
            />
            <KpiTile
              label="Rejections (24h)"
              value={formatCount(engine.state.data?.rejections24h)}
              hint="Verdicts that refused a payment outright."
            />
          </KpiRow>

          <Panel
            title="Outcome by risk band"
            description="Each bar is one band, split by what the platform did. The completed share of a HIGH band is how you tell whether the thresholds sit where you think they do."
          >
            <BarChart
              caption="Payments by risk band and outcome"
              stacked
              labels={data.bands.map((band) => humanise(band.riskLevel))}
              formatValue={formatCount}
              series={[
                {
                  id: 'completed',
                  label: 'Completed',
                  colour: COMPLETED,
                  values: data.bands.map((band) => band.completed),
                },
                {
                  id: 'onHold',
                  label: 'On hold',
                  colour: HELD,
                  values: data.bands.map((band) => band.onHold),
                },
                {
                  id: 'rejected',
                  label: 'Rejected',
                  colour: STOPPED,
                  values: data.bands.map((band) => band.rejected),
                },
                {
                  id: 'other',
                  label: 'Other',
                  colour: '#7c8798',
                  // The remainder, so the stack totals the band. Clamped at zero: an
                  // event arriving mid-query could make the parts briefly exceed the
                  // whole, and a negative segment would draw upward out of its bar.
                  values: data.bands.map((band) =>
                    Math.max(0, band.payments - band.completed - band.onHold - band.rejected),
                  ),
                },
              ]}
              emptyMessage="Nothing was scored in this window."
            />

            <DataTable
              caption="Risk bands with counts and value"
              columns={bandColumns}
              rows={data.bands}
              rowKey={(row) => row.riskLevel}
              empty="Nothing was scored in this window."
            />
          </Panel>

          <Panel
            title="Score distribution"
            description={
              engine.state.data
                ? 'Ten-point buckets of the engine score. A cluster building just below a threshold is worth watching: it is where scoring drift shows up first.'
                : 'Ten-point buckets of the engine score.'
            }
          >
            <BarChart
              caption="Payments by engine score, split by outcome"
              stacked
              labels={data.histogram.map((bucket) => `${bucket.bucketFloor}`)}
              formatValue={formatCount}
              series={[
                {
                  id: 'completed',
                  label: 'Completed',
                  colour: COMPLETED,
                  values: data.histogram.map((bucket) => bucket.completed),
                },
                {
                  id: 'stopped',
                  label: 'Stopped',
                  colour: STOPPED,
                  values: data.histogram.map((bucket) => bucket.stopped),
                },
                {
                  id: 'other',
                  label: 'Still running',
                  colour: '#7c8798',
                  values: data.histogram.map((bucket) =>
                    Math.max(0, bucket.payments - bucket.completed - bucket.stopped),
                  ),
                },
              ]}
              emptyMessage="No scored payments in this window."
            />
            <p className="panel__note">
              Buckets are labelled by their lower bound. Payments the engine never scored are not
              in this chart at all — they are counted as <strong>unscored</strong> above.
            </p>
          </Panel>
        </>
      ) : null}
    </div>
  );
}
