import type { ReactElement } from 'react';
import { band, bandCentre, niceScale, seriesMax, stackSegments, toHeight, toY } from './scale';

export interface BarSeries {
  id: string;
  label: string;
  values: number[];
  colour: string;
}

/**
 * A bar chart, optionally stacked, drawn as SVG.
 *
 * Used for the risk-band distribution and the score histogram. Stacking is what makes
 * the risk view readable: the question is not "how many HIGH payments were there" but
 * "of the HIGH ones, how many were completed anyway", and a stacked bar answers both
 * in the same glyph.
 *
 * Stacked mode sums the series per position to find the axis maximum. Taking the max
 * instead would put the top of every stack above the top gridline — a chart that is
 * wrong in a way that still looks like a chart.
 */
export function BarChart({
  series,
  labels,
  stacked = false,
  height = 240,
  width = 720,
  formatValue,
  caption,
  emptyMessage = 'No activity in this window.',
}: {
  series: ReadonlyArray<BarSeries>;
  labels: ReadonlyArray<string>;
  stacked?: boolean;
  height?: number;
  width?: number;
  formatValue: (value: number) => string;
  caption: string;
  emptyMessage?: string;
}): ReactElement {
  const padding = { top: 12, right: 16, bottom: 40, left: 56 };
  const innerWidth = width - padding.left - padding.right;
  const innerHeight = height - padding.top - padding.bottom;

  if (labels.length === 0) {
    return (
      <figure className="chart chart--empty">
        <figcaption>{caption}</figcaption>
        <p className="chart__empty">{emptyMessage}</p>
      </figure>
    );
  }

  const scale = niceScale(
    seriesMax(
      series.map((bar) => bar.values),
      stacked,
    ),
    4,
  );

  const slot = band(labels.length, innerWidth, 0.68);
  // Side-by-side bars share one slot between them. Stacked bars each occupy the
  // whole slot, since they sit on top of one another.
  const groupWidth = stacked ? slot.width : slot.width / Math.max(1, series.length);

  return (
    <figure className="chart">
      <figcaption className="chart__caption">{caption}</figcaption>

      <svg
        className="chart__svg"
        viewBox={`0 0 ${width} ${height}`}
        role="img"
        aria-label={`${caption}. ${describeBars(series, labels, formatValue)}`}
        preserveAspectRatio="none"
      >
        <g transform={`translate(${padding.left},${padding.top})`}>
          {scale.ticks.map((tick) => {
            const y = toY(tick, scale.max, innerHeight);
            return (
              <g key={tick}>
                <line className="chart__gridline" x1={0} x2={innerWidth} y1={y} y2={y} />
                <text className="chart__tick" x={-8} y={y} dy="0.32em" textAnchor="end">
                  {formatValue(tick)}
                </text>
              </g>
            );
          })}

          {labels.map((label, index) => {
            const slotLeft = slot.step * index + slot.offset;

            if (stacked) {
              const column = series.map((bar) => bar.values[index] ?? 0);
              const segments = stackSegments(column, scale.max, innerHeight);
              return (
                <g key={label} data-bucket={label}>
                  {segments.map((segment, seriesIndex) => {
                    const source = series[seriesIndex];
                    if (!source || segment.height <= 0) {
                      // A zero-height rect is invisible anyway, and skipping it keeps
                      // the DOM proportional to the data rather than to the grid.
                      return null;
                    }
                    return (
                      <rect
                        key={source.id}
                        className="chart__bar"
                        data-series={source.id}
                        x={slotLeft}
                        y={segment.y}
                        width={slot.width}
                        height={segment.height}
                        fill={source.colour}
                      >
                        <title>{`${label} · ${source.label}: ${formatValue(source.values[index] ?? 0)}`}</title>
                      </rect>
                    );
                  })}
                </g>
              );
            }

            return (
              <g key={label} data-bucket={label}>
                {series.map((bar, seriesIndex) => {
                  const value = bar.values[index] ?? 0;
                  const barHeight = toHeight(value, scale.max, innerHeight);
                  if (barHeight <= 0) {
                    return null;
                  }
                  return (
                    <rect
                      key={bar.id}
                      className="chart__bar"
                      data-series={bar.id}
                      x={slotLeft + groupWidth * seriesIndex}
                      y={innerHeight - barHeight}
                      width={groupWidth}
                      height={barHeight}
                      fill={bar.colour}
                    >
                      <title>{`${label} · ${bar.label}: ${formatValue(value)}`}</title>
                    </rect>
                  );
                })}
              </g>
            );
          })}

          <line className="chart__axis" x1={0} x2={innerWidth} y1={innerHeight} y2={innerHeight} />

          {labels.map((label, index) => (
            <text
              key={label}
              className="chart__tick"
              x={bandCentre(index, labels.length, innerWidth)}
              y={innerHeight + 18}
              textAnchor="middle"
            >
              {label}
            </text>
          ))}
        </g>
      </svg>

      <ul className="chart__legend">
        {series.map((bar) => (
          <li key={bar.id}>
            <span className="chart__swatch" style={{ backgroundColor: bar.colour }} aria-hidden />
            {bar.label}
            <span className="chart__legend-value">
              {formatValue(bar.values.reduce((total, value) => total + value, 0))}
            </span>
          </li>
        ))}
      </ul>
    </figure>
  );
}

function describeBars(
  series: ReadonlyArray<BarSeries>,
  labels: ReadonlyArray<string>,
  formatValue: (value: number) => string,
): string {
  return labels
    .map((label, index) => {
      const parts = series.map((bar) => `${bar.label} ${formatValue(bar.values[index] ?? 0)}`);
      return `${label}: ${parts.join(', ')}`;
    })
    .join('. ');
}
