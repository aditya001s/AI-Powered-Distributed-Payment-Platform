/**
 * The arithmetic behind the charts.
 *
 * There is no chart library here. Two reasons, and the second is the one that
 * mattered: a charting dependency is a large surface that churns on its own
 * schedule for a dashboard that needs a line and some bars, and — more usefully —
 * pulling the scaling out as plain functions makes it testable. Every mistake a
 * hand-drawn SVG chart can make is an arithmetic mistake: a division by an empty
 * dataset, an axis that starts somewhere other than zero, a bar drawn with a
 * negative height. Those are all assertions in a unit test when the maths lives
 * here, and a shrug at a wrong-looking picture when it lives inside JSX.
 *
 * Everything in this file is pure: numbers in, numbers out, no DOM.
 */

export interface NiceScale {
  /** The axis maximum. Always greater than zero, so nothing ever divides by it and gets NaN. */
  max: number;
  /** Distance between ticks. */
  step: number;
  /** Tick values from 0 to `max` inclusive. */
  ticks: number[];
}

/**
 * Rounds a rough step up to a readable one: 1, 2, 2.5, 5 or 10 times a power of ten.
 *
 * Axis labels of 0, 137, 274 are technically correct and useless. A reader compares
 * a bar against the gridline nearest it, which only works when the gridlines are at
 * numbers they can do mental arithmetic with.
 */
function niceStep(rough: number, integral: boolean): number {
  if (!Number.isFinite(rough) || rough <= 0) {
    return 1;
  }
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const normalised = rough / magnitude;

  let nice: number;
  if (normalised <= 1) {
    nice = 1;
  } else if (normalised <= 2) {
    nice = 2;
    // 2.5 is a perfectly readable step for money, and a nonsense one for a count of
    // payments: an axis labelled 0, 2.5, 5 implies half a payment exists.
  } else if (normalised <= 2.5 && !integral) {
    nice = 2.5;
  } else if (normalised <= 5) {
    nice = 5;
  } else {
    nice = 10;
  }

  const step = nice * magnitude;
  return integral ? Math.max(1, Math.ceil(step)) : step;
}

/**
 * Chooses an axis for data whose largest value is `rawMax`.
 *
 * The axis always starts at zero. Charts that start an axis at the data minimum
 * turn a 2% change into a cliff, which is how a normal Tuesday gets escalated as an
 * incident. For counts of payments and sums of money, zero is the only honest floor.
 *
 * An empty or all-zero dataset yields `max: tickCount` with a step of 1 rather than
 * a zero-height axis. That is what keeps a chart with no data rendering a flat line
 * along the bottom instead of a set of `NaN` coordinates, which the browser draws as
 * nothing at all — indistinguishable from the chart having failed to load.
 */
export function niceScale(rawMax: number, tickCount = 4, integral = true): NiceScale {
  const count = Math.max(1, Math.trunc(tickCount));

  if (!Number.isFinite(rawMax) || rawMax <= 0) {
    return { max: count, step: 1, ticks: sequence(0, 1, count) };
  }

  const step = niceStep(rawMax / count, integral);
  // Ceiling, not round: rounding down would put the tallest bar above the top
  // gridline and, with a naive renderer, outside the plot area entirely.
  const max = step * Math.ceil(rawMax / step);
  const steps = Math.round(max / step);
  return { max, step, ticks: sequence(0, step, steps) };
}

function sequence(from: number, step: number, count: number): number[] {
  const values: number[] = [];
  for (let index = 0; index <= count; index += 1) {
    // Accumulating `previous + step` would drift on a fractional step
    // (0.1 + 0.2 !== 0.3), leaving a gridline label of 0.30000000000000004.
    values.push(from + step * index);
  }
  return values;
}

/**
 * Largest value across several series, for a shared axis.
 *
 * Stacked series must be summed per position rather than maxed, or the top of the
 * stack lands above the axis. `stacked` picks which.
 */
export function seriesMax(series: ReadonlyArray<ReadonlyArray<number>>, stacked = false): number {
  let max = 0;
  const length = series.reduce((longest, values) => Math.max(longest, values.length), 0);

  for (let index = 0; index < length; index += 1) {
    let value = 0;
    for (const values of series) {
      const candidate = values[index] ?? 0;
      const usable = Number.isFinite(candidate) ? Math.max(0, candidate) : 0;
      value = stacked ? value + usable : Math.max(value, usable);
    }
    max = Math.max(max, value);
  }
  return max;
}

/**
 * Converts a value to a y pixel, measured from the top of the plot.
 *
 * SVG's y axis grows downward while a chart's grows upward, so this inverts. Getting
 * that wrong produces a chart that is upside down but otherwise entirely plausible,
 * which is the worst kind of wrong: a rising failure rate would look like a
 * recovering one.
 */
export function toY(value: number, max: number, innerHeight: number): number {
  if (!Number.isFinite(value) || max <= 0 || innerHeight <= 0) {
    return innerHeight > 0 ? innerHeight : 0;
  }
  const clamped = Math.min(Math.max(value, 0), max);
  return innerHeight - (clamped / max) * innerHeight;
}

/** Height of a bar for `value`, never negative and never taller than the plot. */
export function toHeight(value: number, max: number, innerHeight: number): number {
  if (!Number.isFinite(value) || value <= 0 || max <= 0 || innerHeight <= 0) {
    return 0;
  }
  return Math.min(innerHeight, (Math.min(value, max) / max) * innerHeight);
}

export interface Band {
  /** Distance from one slot to the next. */
  step: number;
  /** Left edge of the drawn bar within its slot. */
  offset: number;
  /** Drawn width of a bar. */
  width: number;
}

/**
 * Divides the plot into `count` equal slots with a gap between bars.
 *
 * A single data point gets the whole width rather than a bar of infinite width from
 * dividing by zero; a `count` of zero gets a zero-width band and the caller draws
 * nothing.
 */
export function band(count: number, innerWidth: number, fill = 0.7): Band {
  if (count <= 0 || innerWidth <= 0) {
    return { step: 0, offset: 0, width: 0 };
  }
  const step = innerWidth / count;
  const ratio = Math.min(Math.max(fill, 0.05), 1);
  const width = step * ratio;
  return { step, offset: (step - width) / 2, width };
}

/** Centre x of slot `index`, for a tick label or a line vertex. */
export function bandCentre(index: number, count: number, innerWidth: number): number {
  if (count <= 0 || innerWidth <= 0) {
    return 0;
  }
  if (count === 1) {
    // One point sits in the middle. Placing it at x=0 would hang half a marker off
    // the left edge of the plot and read as a clipped chart.
    return innerWidth / 2;
  }
  return (innerWidth / count) * (index + 0.5);
}

/**
 * Evenly spaced x for a line, spanning edge to edge.
 *
 * Lines use the full width while bars are inset by half a slot — a line that stops
 * short of the right-hand axis looks like missing data rather than a design choice.
 */
export function lineX(index: number, count: number, innerWidth: number): number {
  if (count <= 1 || innerWidth <= 0) {
    return count === 1 ? innerWidth / 2 : 0;
  }
  return (innerWidth / (count - 1)) * index;
}

/**
 * Builds the `points` attribute for a `<polyline>`.
 *
 * Coordinates are rounded to two decimals. Full float precision produces an
 * enormous attribute string for no visible difference at screen resolution.
 */
export function polylinePoints(
  values: ReadonlyArray<number>,
  max: number,
  innerWidth: number,
  innerHeight: number,
): string {
  return values
    .map((value, index) => {
      const x = lineX(index, values.length, innerWidth);
      const y = toY(value, max, innerHeight);
      return `${round(x)},${round(y)}`;
    })
    .join(' ');
}

/**
 * Closed path for the shaded area under a line.
 *
 * Returns an empty string for empty data. An empty `d` renders nothing, whereas a
 * malformed one is a console error and, in some browsers, a partially drawn shape.
 */
export function areaPath(
  values: ReadonlyArray<number>,
  max: number,
  innerWidth: number,
  innerHeight: number,
): string {
  if (values.length === 0 || innerWidth <= 0 || innerHeight <= 0) {
    return '';
  }
  const points = values
    .map((value, index) => {
      const x = lineX(index, values.length, innerWidth);
      const y = toY(value, max, innerHeight);
      return `${index === 0 ? 'M' : 'L'}${round(x)} ${round(y)}`;
    })
    .join(' ');

  const lastX = lineX(values.length - 1, values.length, innerWidth);
  const firstX = lineX(0, values.length, innerWidth);
  return `${points} L${round(lastX)} ${round(innerHeight)} L${round(firstX)} ${round(innerHeight)} Z`;
}

export interface StackedSegment {
  /** Distance from the top of the plot to the top of this segment. */
  y: number;
  height: number;
}

/**
 * Lays out one stacked bar from bottom to top.
 *
 * Positions are accumulated in value space and converted once, rather than
 * converting each segment and adding pixels. Adding rounded pixel heights leaves
 * sub-pixel gaps between segments, which at a glance look like a hairline grid
 * drawn through the bars.
 */
export function stackSegments(
  values: ReadonlyArray<number>,
  max: number,
  innerHeight: number,
): StackedSegment[] {
  const segments: StackedSegment[] = [];
  let cumulative = 0;

  for (const value of values) {
    const usable = Number.isFinite(value) ? Math.max(0, value) : 0;
    const bottom = toY(cumulative, max, innerHeight);
    cumulative += usable;
    const top = toY(cumulative, max, innerHeight);
    segments.push({ y: top, height: Math.max(0, bottom - top) });
  }
  return segments;
}

/**
 * Picks roughly `desired` label positions out of `count`.
 *
 * A minute-granularity chart over six hours has 360 buckets; labelling all of them
 * produces a black smear along the axis. Returns indices, always including the first
 * and last so the axis states the range it covers.
 */
export function labelIndices(count: number, desired = 6): number[] {
  if (count <= 0) {
    return [];
  }
  if (count <= desired) {
    return Array.from({ length: count }, (_, index) => index);
  }
  const stride = Math.ceil(count / Math.max(1, desired - 1));
  const indices: number[] = [];
  for (let index = 0; index < count - 1; index += stride) {
    indices.push(index);
  }
  indices.push(count - 1);
  return indices;
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}
