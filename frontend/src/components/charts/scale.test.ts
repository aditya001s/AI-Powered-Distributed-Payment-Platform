import { describe, expect, it } from 'vitest';
import {
  areaPath,
  band,
  bandCentre,
  labelIndices,
  lineX,
  niceScale,
  polylinePoints,
  seriesMax,
  stackSegments,
  toHeight,
  toY,
} from './scale';

/**
 * The chart arithmetic.
 *
 * <p>This is the reason the charts are hand-drawn rather than delegated to a library: every way an SVG chart
 * can be wrong is an arithmetic mistake, and arithmetic is testable. A division by an empty dataset, an axis
 * that starts somewhere other than zero, a bar with a negative height, a chart drawn upside down — each of
 * those is an assertion below, and each would otherwise be a shrug at a picture that looked plausible.
 *
 * <p>The empty and all-zero cases get the most attention. They are what a quiet night produces, and the
 * failure mode is `NaN` coordinates, which the browser renders as nothing at all — indistinguishable from
 * the chart having failed to load.
 */

describe('niceScale', () => {
  it('rounds the axis up to a readable step', () => {
    /*
     * 137 over four ticks is a rough step of 34.25, which rounds up to 50. The axis is then the
     * smallest multiple of 50 that covers 137, which is 150 — three steps rather than the four
     * asked for. `tickCount` is a target, not a promise: forcing exactly four would mean either a
     * step of 34.25 or an axis of 200 with a lot of empty space above the data.
     */
    expect(niceScale(137, 4)).toEqual({ max: 150, step: 50, ticks: [0, 50, 100, 150] });
  });

  /** Ceiling, not round: rounding down would put the tallest bar above the top gridline. */
  it('never puts the data above the axis maximum', () => {
    for (const rawMax of [1, 7, 23, 99, 101, 137, 1_001, 87_654]) {
      const scale = niceScale(rawMax, 4);
      expect(scale.max, `axis for ${rawMax}`).toBeGreaterThanOrEqual(rawMax);
      // And the top gridline is the maximum, so the tallest bar meets a labelled line.
      expect(scale.ticks.at(-1), `top tick for ${rawMax}`).toBe(scale.max);
    }
  });

  /**
   * The case that produces NaN if the axis is allowed to be zero. An empty window is a real answer, and the
   * chart has to render a flat line along the bottom rather than a set of undrawable coordinates.
   */
  it('gives an empty or all-zero dataset a usable axis', () => {
    expect(niceScale(0, 4)).toEqual({ max: 4, step: 1, ticks: [0, 1, 2, 3, 4] });
    expect(niceScale(Number.NaN, 4).max).toBeGreaterThan(0);
    expect(niceScale(-5, 4).max).toBeGreaterThan(0);
  });

  /**
   * Counts get integer steps. An axis labelled 0, 2.5, 5 implies half a payment exists, which is the kind of
   * detail that quietly costs a reader's confidence in every other number on the page.
   */
  it('keeps count axes on whole numbers', () => {
    const scale = niceScale(9, 4, true);
    expect(scale.ticks.every((tick) => Number.isInteger(tick))).toBe(true);
  });

  /** Money may use a 2.5 step, where a fractional gridline is perfectly ordinary. */
  it('allows a fractional step for non-integral data', () => {
    expect(niceScale(9, 4, false).step).toBe(2.5);
  });

  /**
   * Ticks are computed as `start + step * index` rather than accumulated. Accumulating drifts on a
   * fractional step and produces a gridline labelled 0.30000000000000004.
   */
  it('produces exact ticks on a fractional step', () => {
    expect(niceScale(1, 4, false).ticks).toEqual([0, 0.25, 0.5, 0.75, 1]);
  });

  it('always starts the axis at zero', () => {
    expect(niceScale(1_000, 4).ticks[0]).toBe(0);
  });
});

describe('seriesMax', () => {
  it('takes the largest single value when series overlay', () => {
    expect(
      seriesMax(
        [
          [1, 8, 3],
          [4, 2, 6],
        ],
        false,
      ),
    ).toBe(8);
  });

  /**
   * Stacked series must be summed per position. Taking the maximum instead puts the top of every stack above
   * the top gridline — a chart that is wrong while still looking like a chart.
   */
  it('sums per position when series stack', () => {
    expect(
      seriesMax(
        [
          [1, 8, 3],
          [4, 2, 6],
        ],
        true,
      ),
    ).toBe(10);
  });

  it('ignores negative and non-finite values rather than propagating them', () => {
    expect(seriesMax([[-5, Number.NaN, 3]])).toBe(3);
  });

  it('handles ragged series and an empty input', () => {
    expect(seriesMax([[1, 2, 3], [5]], true)).toBe(6);
    expect(seriesMax([])).toBe(0);
    expect(seriesMax([[]])).toBe(0);
  });
});

describe('toY', () => {
  /**
   * SVG's y axis grows downward while a chart's grows upward. Getting the inversion wrong produces a chart
   * that is upside down but otherwise plausible — a rising failure rate would look like a recovery.
   */
  it('inverts the axis, so a larger value sits higher', () => {
    expect(toY(0, 100, 200)).toBe(200);
    expect(toY(100, 100, 200)).toBe(0);
    expect(toY(50, 100, 200)).toBe(100);
  });

  it('clamps out-of-range values into the plot', () => {
    expect(toY(150, 100, 200)).toBe(0);
    expect(toY(-10, 100, 200)).toBe(200);
  });

  /** No NaN, whatever it is handed. A NaN coordinate makes the whole path invisible. */
  it('never returns NaN for a degenerate axis', () => {
    expect(toY(5, 0, 200)).toBe(200);
    expect(toY(Number.NaN, 100, 200)).toBe(200);
    expect(toY(5, 100, 0)).toBe(0);
  });
});

describe('toHeight', () => {
  it('scales a bar to the axis', () => {
    expect(toHeight(50, 100, 200)).toBe(100);
    expect(toHeight(100, 100, 200)).toBe(200);
  });

  /** A negative height draws upward out of its bar in some renderers and nothing at all in others. */
  it('never returns a negative or oversized height', () => {
    expect(toHeight(-5, 100, 200)).toBe(0);
    expect(toHeight(150, 100, 200)).toBe(200);
    expect(toHeight(Number.NaN, 100, 200)).toBe(0);
    expect(toHeight(5, 0, 200)).toBe(0);
  });
});

describe('band', () => {
  it('divides the plot into equal slots with a gap', () => {
    const slot = band(4, 400, 0.5);
    expect(slot.step).toBe(100);
    expect(slot.width).toBe(50);
    expect(slot.offset).toBe(25);
  });

  /** A count of zero would divide by zero and produce a bar of infinite width. */
  it('returns a zero band for no data', () => {
    expect(band(0, 400)).toEqual({ step: 0, offset: 0, width: 0 });
    expect(band(4, 0)).toEqual({ step: 0, offset: 0, width: 0 });
  });

  it('gives a single bucket the whole width', () => {
    expect(band(1, 400, 1).width).toBe(400);
  });

  it('clamps an absurd fill ratio', () => {
    expect(band(2, 200, 5).width).toBeLessThanOrEqual(100);
    expect(band(2, 200, -1).width).toBeGreaterThan(0);
  });
});

describe('bandCentre and lineX', () => {
  it('places bar labels at slot centres', () => {
    expect(bandCentre(0, 4, 400)).toBe(50);
    expect(bandCentre(3, 4, 400)).toBe(350);
  });

  /**
   * A single point sits in the middle. At x=0 half the marker hangs off the left edge of the plot and reads
   * as a clipped chart.
   */
  it('centres a lone point rather than pinning it to the edge', () => {
    expect(bandCentre(0, 1, 400)).toBe(200);
    expect(lineX(0, 1, 400)).toBe(200);
  });

  /** Lines span edge to edge: one that stops short of the axis looks like missing data. */
  it('spans the full width for a line', () => {
    expect(lineX(0, 5, 400)).toBe(0);
    expect(lineX(4, 5, 400)).toBe(400);
    expect(lineX(2, 5, 400)).toBe(200);
  });

  it('returns zero rather than NaN for degenerate input', () => {
    expect(bandCentre(0, 0, 400)).toBe(0);
    expect(lineX(0, 0, 400)).toBe(0);
  });
});

describe('polylinePoints', () => {
  it('builds coordinate pairs spanning the plot', () => {
    expect(polylinePoints([0, 50, 100], 100, 200, 100)).toBe('0,100 100,50 200,0');
  });

  /** Rounded to two decimals: full float precision makes an enormous attribute for no visible gain. */
  it('rounds coordinates', () => {
    expect(polylinePoints([1, 2, 3], 7, 100, 100)).not.toContain('.000');
  });

  it('returns an empty string for no data', () => {
    expect(polylinePoints([], 100, 200, 100)).toBe('');
  });

  /** Every coordinate has to be a number, or the browser silently drops the whole polyline. */
  it('produces no NaN even from an all-zero series', () => {
    expect(polylinePoints([0, 0, 0], niceScale(0).max, 200, 100)).not.toContain('NaN');
  });
});

describe('areaPath', () => {
  it('closes the shape along the baseline', () => {
    const path = areaPath([0, 100], 100, 200, 100);
    expect(path).toBe('M0 100 L200 0 L200 100 L0 100 Z');
  });

  /**
   * An empty `d` renders nothing, which is correct. A malformed one is a console error and, in some
   * browsers, a partially drawn shape.
   */
  it('returns an empty path rather than a malformed one', () => {
    expect(areaPath([], 100, 200, 100)).toBe('');
    expect(areaPath([1, 2], 100, 0, 100)).toBe('');
  });
});

describe('stackSegments', () => {
  it('stacks segments bottom to top without gaps', () => {
    const segments = stackSegments([25, 25, 50], 100, 200);

    expect(segments).toHaveLength(3);
    expect(segments[0]).toEqual({ y: 150, height: 50 });
    expect(segments[1]).toEqual({ y: 100, height: 50 });
    expect(segments[2]).toEqual({ y: 0, height: 100 });
  });

  /**
   * Positions accumulate in value space and convert once. Adding rounded pixel heights leaves sub-pixel gaps
   * that read as a hairline grid drawn through the bars.
   */
  it('leaves no gap between adjacent segments', () => {
    const segments = stackSegments([33, 33, 34], 100, 200);

    for (let index = 1; index < segments.length; index += 1) {
      const lower = segments[index - 1];
      const upper = segments[index];
      expect(upper && lower && upper.y + upper.height).toBeCloseTo(lower?.y ?? 0, 10);
    }
  });

  it('gives a zero or negative value a zero-height segment', () => {
    const segments = stackSegments([0, -5, 50], 100, 200);
    expect(segments[0]?.height).toBe(0);
    expect(segments[1]?.height).toBe(0);
    expect(segments[2]?.height).toBe(100);
  });

  it('produces nothing for an empty stack', () => {
    expect(stackSegments([], 100, 200)).toEqual([]);
  });
});

describe('labelIndices', () => {
  it('labels every bucket when there are few', () => {
    expect(labelIndices(4, 6)).toEqual([0, 1, 2, 3]);
  });

  /**
   * A minute-granularity chart over six hours has 360 buckets; labelling all of them produces a black smear
   * along the axis.
   */
  it('thins the labels on a dense axis', () => {
    const indices = labelIndices(360, 6);

    expect(indices.length).toBeLessThanOrEqual(7);
    // The first and last are always present, so the axis states the range it covers.
    expect(indices[0]).toBe(0);
    expect(indices.at(-1)).toBe(359);
  });

  it('returns nothing for an empty axis', () => {
    expect(labelIndices(0)).toEqual([]);
  });

  it('never repeats an index', () => {
    const indices = labelIndices(13, 6);
    expect(new Set(indices).size).toBe(indices.length);
  });
});
