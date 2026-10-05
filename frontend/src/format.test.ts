import { describe, expect, it } from 'vitest';
import {
  ABSENT,
  formatAgo,
  formatCount,
  formatDuration,
  formatMillis,
  formatMoney,
  formatMoneyCompact,
  formatRate,
  formatScore,
  humanise,
  minorUnitExponent,
  shortId,
} from './format';

/**
 * Formatting, tested exhaustively because this is where the money is rendered.
 *
 * <p>Two themes run through these cases, and both are about the same mistake in different clothes.
 *
 * <p>The first is <strong>absent is not zero</strong>. A null success rate rendered as "0.0%" tells an
 * operator every payment failed on a morning when none have finished; a null amount rendered as "₹0.00"
 * tells them a payment moved nothing. Every formatter returns an em dash for absence, and the tests say so
 * one function at a time.
 *
 * <p>The second is <strong>no floating point in the money path</strong>. `formatMoney` splits minor units
 * with BigInt rather than dividing by 100, and the cases below include the values where a float would show.
 */

describe('formatMoney', () => {
  it('renders exact minor units without touching floating point', () => {
    expect(formatMoney({ amount: '', amountMinor: 250000, currency: 'INR' })).toBe('₹2,500.00');
    expect(formatMoney({ amount: '', amountMinor: 1, currency: 'INR' })).toBe('₹0.01');
    expect(formatMoney({ amount: '', amountMinor: 0, currency: 'INR' })).toBe('₹0.00');
  });

  /*
   * The values a float would get wrong. 1999 / 100 is 19.989999999999998 in binary, and
   * 8237 / 100 is 82.37000000000001. Both round to the right answer *this time*, which is
   * exactly why a passing spot check is not evidence: the integer split cannot be wrong at all.
   */
  it('renders the values where a float division drifts', () => {
    expect(formatMoney({ amount: '', amountMinor: 1999, currency: 'INR' })).toBe('₹19.99');
    expect(formatMoney({ amount: '', amountMinor: 8237, currency: 'INR' })).toBe('₹82.37');
    expect(formatMoney({ amount: '', amountMinor: 111111111, currency: 'USD' })).toBe(
      '$1,111,111.11',
    );
  });

  it('groups Indian amounts in lakhs and crores, and others in thousands', () => {
    // ₹1 crore, in minor units. The grouping is how the figure will be discussed by the
    // people reading it: two-digit groups above the first thousand.
    expect(formatMoney({ amount: '', amountMinor: 1_000_000_000, currency: 'INR' })).toBe(
      '₹1,00,00,000.00',
    );
    // The same integer, grouped in threes, because that is what a dollar reader expects.
    expect(formatMoney({ amount: '', amountMinor: 1_000_000_000, currency: 'USD' })).toBe(
      '$10,000,000.00',
    );
  });

  it('handles a negative amount without losing the sign inside the symbol', () => {
    expect(formatMoney({ amount: '', amountMinor: -12345, currency: 'INR' })).toBe('-₹123.45');
  });

  /*
   * JPY has no minor unit. Dividing by 100 would understate every yen figure a hundredfold,
   * which is the kind of error that looks like a data problem rather than a formatting one.
   */
  it('respects a currency with no minor unit', () => {
    expect(minorUnitExponent('JPY')).toBe(0);
    // No decimal part at all, and the ISO code stands in for a symbol the map does not carry.
    // A default exponent of two here would render ¥1,500 as ¥15.00.
    expect(formatMoney({ amount: '', amountMinor: 1500, currency: 'JPY' })).toBe('JPY 1,500');
  });

  it('respects a three-decimal currency', () => {
    expect(minorUnitExponent('KWD')).toBe(3);
    expect(formatMoney({ amount: '', amountMinor: 1234, currency: 'KWD' })).toBe('KWD 1.234');
  });

  /** An unknown currency falls back to its ISO code, which is unambiguous rather than pretty. */
  it('falls back to the ISO code for an unknown currency', () => {
    expect(formatMoney({ amount: '', amountMinor: 100, currency: 'XYZ' })).toBe('XYZ 1.00');
  });

  it('renders absence as an em dash, not as zero', () => {
    expect(formatMoney(null)).toBe(ABSENT);
    expect(formatMoney(undefined)).toBe(ABSENT);
  });

  /**
   * Beyond 2^53 the value has already lost precision in JSON.parse, before this function saw it. A
   * visible marker beats a confidently wrong figure.
   */
  it('marks a value that JSON could not carry exactly', () => {
    expect(formatMoney({ amount: '', amountMinor: 2 ** 53 + 2, currency: 'INR' })).toBe('₹?');
    expect(formatMoney({ amount: '', amountMinor: Number.NaN, currency: 'INR' })).toBe('₹?');
  });
});

describe('formatMoneyCompact', () => {
  it('uses crore and lakh for rupees', () => {
    expect(formatMoneyCompact({ amount: '', amountMinor: 1_500_000_000, currency: 'INR' })).toBe(
      '₹1.50 Cr',
    );
    expect(formatMoneyCompact({ amount: '', amountMinor: 25_000_000, currency: 'INR' })).toBe(
      '₹2.50 L',
    );
  });

  it('uses K, M and B elsewhere', () => {
    expect(formatMoneyCompact({ amount: '', amountMinor: 150_000, currency: 'USD' })).toBe('$1.5K');
    expect(formatMoneyCompact({ amount: '', amountMinor: 500_000_000, currency: 'USD' })).toBe(
      '$5.00M',
    );
  });

  it('renders absence as an em dash', () => {
    expect(formatMoneyCompact(null)).toBe(ABSENT);
  });
});

describe('formatRate', () => {
  it('renders a 0-1 rate as a percentage', () => {
    expect(formatRate(0.9512)).toBe('95.1%');
    expect(formatRate(1)).toBe('100.0%');
  });

  /**
   * The distinction the whole tile depends on. Zero means every terminal payment failed; null means none
   * reached a terminal state, and rendering the second as the first would have somebody paging an on-call
   * engineer on a quiet morning.
   */
  it('keeps zero and absent apart', () => {
    expect(formatRate(0)).toBe('0.0%');
    expect(formatRate(null)).toBe(ABSENT);
    expect(formatRate(undefined)).toBe(ABSENT);
  });
});

describe('formatCount', () => {
  it('groups and keeps zero as zero', () => {
    expect(formatCount(1234567)).toBe('1,234,567');
    expect(formatCount(0)).toBe('0');
  });

  it('renders absence as an em dash', () => {
    expect(formatCount(null)).toBe(ABSENT);
    expect(formatCount(undefined)).toBe(ABSENT);
  });
});

describe('formatScore', () => {
  it('rounds a 0-100 score', () => {
    expect(formatScore(82.6)).toBe('83');
    expect(formatScore(0)).toBe('0');
  });

  it('renders absence as an em dash, because unscored is not zero risk', () => {
    expect(formatScore(null)).toBe(ABSENT);
  });
});

describe('formatMillis', () => {
  it('promotes to seconds once the milliseconds stop being useful', () => {
    expect(formatMillis(420)).toBe('420 ms');
    expect(formatMillis(4_100)).toBe('4.10 s');
    expect(formatMillis(95_000)).toBe('1m');
  });

  it('renders absence as an em dash, because an unfinished payment has no latency', () => {
    expect(formatMillis(null)).toBe(ABSENT);
  });
});

describe('formatDuration', () => {
  it('shows the largest two units that carry information', () => {
    expect(formatDuration(45)).toBe('45s');
    expect(formatDuration(90)).toBe('1m');
    expect(formatDuration(8_040)).toBe('2h 14m');
    expect(formatDuration(7_200)).toBe('2h');
    expect(formatDuration(90_000)).toBe('1d 1h');
  });

  /** A negative age is clock skew, and clamping to zero is better than rendering "-3s". */
  it('clamps a negative duration', () => {
    expect(formatDuration(-10)).toBe('0s');
  });

  it('renders absence as an em dash', () => {
    expect(formatDuration(null)).toBe(ABSENT);
  });
});

describe('formatAgo', () => {
  const now = Date.parse('2026-03-01T12:00:00Z');

  it('reads as an age relative to the supplied clock', () => {
    expect(formatAgo('2026-03-01T11:58:00Z', now)).toBe('2m ago');
    expect(formatAgo('2026-03-01T09:46:00Z', now)).toBe('2h 14m ago');
  });

  it('says just now inside the first few seconds', () => {
    expect(formatAgo('2026-03-01T11:59:58Z', now)).toBe('just now');
  });

  /**
   * A timestamp in the future is clock skew between services or a machine with a wrong clock. Saying "just
   * now" would hide a real operational problem behind a friendly phrase.
   */
  it('reports a future timestamp as future rather than as just now', () => {
    expect(formatAgo('2026-03-01T12:10:00Z', now)).toBe('in 10m');
  });

  it('renders absence and a malformed timestamp as an em dash', () => {
    expect(formatAgo(null, now)).toBe(ABSENT);
    expect(formatAgo('not a date', now))
      // Without the guard this returns NaN, and NaN propagates into a chart axis.
      .toBe(ABSENT);
  });
});

describe('shortId', () => {
  it('keeps the leading characters, which is what people search on', () => {
    expect(shortId('PAY-01JQABCDEF012345')).toBe('PAY-01JQ…');
    expect(shortId('short')).toBe('short');
  });

  it('renders absence as an em dash', () => {
    expect(shortId(null)).toBe(ABSENT);
  });
});

describe('humanise', () => {
  it('turns a screaming-snake enum into prose', () => {
    expect(humanise('PARTIALLY_REFUNDED')).toBe('Partially refunded');
    expect(humanise('LOW')).toBe('Low');
  });

  it('renders absence as an em dash', () => {
    expect(humanise(null)).toBe(ABSENT);
    expect(humanise('')).toBe(ABSENT);
  });
});
