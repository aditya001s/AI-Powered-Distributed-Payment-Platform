import type { ReactElement } from 'react';
import {
  areaPath,
  labelIndices,
  lineX,
  niceScale,
  polylinePoints,
  seriesMax,
  toY,
} from './scale';

export interface LineSeries {
  id: string;
  label: string;
  values: number[];
  /** A CSS custom property name or colour. Kept alongside a distinct label, never alone. */
  colour: string;
  /** Shades the area under the line. Only ever one series, or the shading obscures the rest. */
  fill?: boolean;
}

/**
 * A multi-series line chart, drawn as SVG.
 *
 * SVG rather than canvas because it is inspectable, scales without redrawing, and —
 * the reason that matters here — its content is real DOM, so a test can assert that
 * a series was drawn and a screen reader can be given something to read.
 *
 * All the arithmetic lives in `scale.ts` and is tested there. This component's job
 * is layout and labels.
 */
export function LineChart({
  series,
  labels,
  height = 240,
  width = 720,
  formatValue,
  formatLabel = (label) => label,
  caption,
  yLabel,
}: {
  series: ReadonlyArray<LineSeries>;
  /** One label per bucket. Length defines the x axis. */
  labels: ReadonlyArray<string>;
  height?: number;
  /** A viewBox width. The SVG scales to its container, so this is a ratio, not pixels. */
  width?: number;
  formatValue: (value: number) => string;
  formatLabel?: (label: string) => string;
  caption: string;
  yLabel?: string;
}): ReactElement {
  const padding = { top: 12, right: 16, bottom: 28, left: 56 };
  const innerWidth = width - padding.left - padding.right;
  const innerHeight = height - padding.top - padding.bottom;

  const scale = niceScale(
    seriesMax(series.map((line) => line.values)),
    4,
  );

  /*
   * An empty window is a real answer, not an error.
   *
   * A quiet night, or a window with no traffic, produces no buckets. Rendering an
   * axis-only chart would leave the reader unsure whether it failed to load, so it
   * says so in words instead.
   */
  if (labels.length === 0) {
    return (
      <figure className="chart chart--empty">
        <figcaption>{caption}</figcaption>
        <p className="chart__empty">No activity in this window.</p>
      </figure>
    );
  }

  const ticks = labelIndices(labels.length, 6);

  return (
    <figure className="chart">
      <figcaption className="chart__caption">{caption}</figcaption>

      <svg
        className="chart__svg"
        viewBox={`0 0 ${width} ${height}`}
        // The chart is a picture of a table that is also on the page or reachable
        // from it, so it is hidden from assistive technology rather than described
        // badly. A summary follows below in text.
        role="img"
        aria-label={`${caption}. ${describeSeries(series, formatValue)}`}
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

          {series.map((line) =>
            line.fill ? (
              <path
                key={`${line.id}-area`}
                className="chart__area"
                d={areaPath(line.values, scale.max, innerWidth, innerHeight)}
                fill={line.colour}
              />
            ) : null,
          )}

          {series.map((line) => (
            <polyline
              key={line.id}
              className="chart__line"
              data-series={line.id}
              points={polylinePoints(line.values, scale.max, innerWidth, innerHeight)}
              fill="none"
              stroke={line.colour}
            />
          ))}

          {/*
            Markers only when the points are far enough apart to be distinguishable.
            At minute granularity over six hours there are 360 of them and they merge
            into a solid band that hides the line itself.
          */}
          {labels.length <= 40
            ? series.map((line) =>
                line.values.map((value, index) => (
                  <circle
                    key={`${line.id}-${index}`}
                    className="chart__marker"
                    cx={lineX(index, line.values.length, innerWidth)}
                    cy={toY(value, scale.max, innerHeight)}
                    r={2.5}
                    fill={line.colour}
                  />
                )),
              )
            : null}

          <line className="chart__axis" x1={0} x2={innerWidth} y1={innerHeight} y2={innerHeight} />

          {ticks.map((index) => (
            <text
              key={index}
              className="chart__tick"
              x={lineX(index, labels.length, innerWidth)}
              y={innerHeight + 18}
              textAnchor={index === 0 ? 'start' : index === labels.length - 1 ? 'end' : 'middle'}
            >
              {formatLabel(labels[index] ?? '')}
            </text>
          ))}
        </g>

        {yLabel ? (
          <text className="chart__axis-label" x={4} y={10}>
            {yLabel}
          </text>
        ) : null}
      </svg>

      <ul className="chart__legend">
        {series.map((line) => (
          <li key={line.id}>
            <span className="chart__swatch" style={{ backgroundColor: line.colour }} aria-hidden />
            {line.label}
            {/* The latest value in the legend. It is the number people actually
                want, and reading it off a line is guesswork. */}
            <span className="chart__legend-value">
              {formatValue(line.values[line.values.length - 1] ?? 0)}
            </span>
          </li>
        ))}
      </ul>
    </figure>
  );
}

/** A one-sentence summary of the chart, for the accessible label. */
function describeSeries(
  series: ReadonlyArray<LineSeries>,
  formatValue: (value: number) => string,
): string {
  return series
    .map((line) => {
      const peak = line.values.reduce((highest, value) => Math.max(highest, value), 0);
      return `${line.label}: latest ${formatValue(line.values[line.values.length - 1] ?? 0)}, peak ${formatValue(peak)}`;
    })
    .join('. ');
}
