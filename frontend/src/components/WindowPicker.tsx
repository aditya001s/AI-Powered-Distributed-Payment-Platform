import type { ReactElement } from 'react';
import type { Granularity } from '../api/types';

/**
 * The time range for every analytics query.
 *
 * Presets rather than two free-text date fields. The granularity is not independent
 * of the span — the backend refuses minute buckets over more than six hours, and
 * rightly so: 43,200 buckets is not a chart. Pairing each span with a granularity
 * that fits makes an invalid combination unreachable from the UI instead of a 400 the
 * operator has to interpret.
 *
 * Deliberately not silently coarsened server-side either. If the API quietly swapped
 * minutes for hours, the chart's own axis labels would be wrong, so it refuses and
 * names the granularity to use. This picker exists so that never happens.
 */
export interface AnalyticsRange {
  id: string;
  label: string;
  /** Span in seconds. */
  seconds: number;
  granularity: Granularity;
  /** Roughly how many buckets this yields, for the description. */
  buckets: number;
}

export const RANGES: ReadonlyArray<AnalyticsRange> = [
  { id: '1h', label: 'Last hour', seconds: 3_600, granularity: 'minute', buckets: 60 },
  { id: '6h', label: 'Last 6 hours', seconds: 21_600, granularity: 'minute', buckets: 360 },
  { id: '24h', label: 'Last 24 hours', seconds: 86_400, granularity: 'hour', buckets: 24 },
  { id: '7d', label: 'Last 7 days', seconds: 604_800, granularity: 'hour', buckets: 168 },
  { id: '30d', label: 'Last 30 days', seconds: 2_592_000, granularity: 'day', buckets: 30 },
  { id: '90d', label: 'Last 90 days', seconds: 7_776_000, granularity: 'day', buckets: 90 },
];

export const DEFAULT_RANGE: AnalyticsRange = RANGES[2] as AnalyticsRange;

export function rangeById(id: string): AnalyticsRange {
  return RANGES.find((range) => range.id === id) ?? DEFAULT_RANGE;
}

/**
 * Turns a range into query bounds.
 *
 * `now` is a parameter so the caller can pin it for the duration of a page load.
 * Recomputing `Date.now()` separately for the overview and the time series would
 * give the two panels windows a few milliseconds apart, which is enough to put a
 * payment in one and not the other and leave a total that does not match its chart.
 */
export function toBounds(
  range: AnalyticsRange,
  now: number,
): { from: string; to: string; granularity: Granularity } {
  return {
    from: new Date(now - range.seconds * 1000).toISOString(),
    to: new Date(now).toISOString(),
    granularity: range.granularity,
  };
}

export function WindowPicker({
  value,
  onChange,
  onRefresh,
  refreshing = false,
  note,
}: {
  value: AnalyticsRange;
  onChange: (range: AnalyticsRange) => void;
  onRefresh?: () => void;
  refreshing?: boolean;
  /** Usually the freshness note, so the range and its staleness sit together. */
  note?: ReactElement;
}): ReactElement {
  return (
    <div className="window-picker">
      <label className="window-picker__label" htmlFor="analytics-range">
        Window
      </label>
      <select
        id="analytics-range"
        className="select"
        value={value.id}
        onChange={(event) => onChange(rangeById(event.target.value))}
      >
        {RANGES.map((range) => (
          <option key={range.id} value={range.id}>
            {range.label} ({range.granularity})
          </option>
        ))}
      </select>

      {note}

      {onRefresh ? (
        <button
          className="button button--quiet"
          type="button"
          onClick={onRefresh}
          disabled={refreshing}
        >
          {refreshing ? 'Refreshing…' : 'Refresh'}
        </button>
      ) : null}
    </div>
  );
}
