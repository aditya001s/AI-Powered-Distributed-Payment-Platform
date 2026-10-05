import type { IsoInstant, MoneyView } from './api/types';

/**
 * Display formatting. Pure functions, no React, no I/O.
 *
 * Kept separate from the components for one reason: this is where the money is
 * rendered, and money rendering is the part of a payments UI that has to be
 * verified rather than eyeballed. A function that takes a value and returns a
 * string can be tested exhaustively; the same logic inlined into JSX cannot.
 */

/** What to show when a value is genuinely absent, as opposed to zero. */
export const ABSENT = '—';

/**
 * Currency symbols, with the ISO code as the fallback.
 *
 * `Intl.NumberFormat` with `style: 'currency'` is deliberately not used. Its output
 * varies between ICU versions and Node builds (`₹1,234.50` versus `INR 1,234.50`
 * versus `₹ 1,234.50`), which makes assertions about money formatting flaky for
 * reasons that have nothing to do with this code. An unknown currency falls back to
 * its code, which is unambiguous rather than pretty.
 */
const SYMBOLS: Readonly<Record<string, string>> = {
  INR: '₹',
  USD: '$',
  EUR: '€',
  GBP: '£',
};

/**
 * Minor-unit exponents for the currencies the platform handles.
 *
 * Two is not a safe universal default in general — JPY and KRW have no minor unit,
 * and dividing their amounts by 100 would understate every figure by a hundredfold.
 * They are listed explicitly so the wrong answer is impossible rather than unlikely.
 */
const EXPONENTS: Readonly<Record<string, number>> = {
  INR: 2,
  USD: 2,
  EUR: 2,
  GBP: 2,
  JPY: 0,
  KRW: 0,
  BHD: 3,
  KWD: 3,
};

/** Indian currency groups in lakhs and crores; everything else in thousands. */
function groupingLocale(currency: string): string {
  return currency === 'INR' ? 'en-IN' : 'en-US';
}

export function minorUnitExponent(currency: string): number {
  return EXPONENTS[currency.toUpperCase()] ?? 2;
}

/**
 * Formats a monetary amount from its exact minor-unit integer.
 *
 * The split into whole and fractional parts is done with `BigInt`, not by dividing
 * a `number` by 100. `12345 / 100` is not exactly `123.45` in binary floating point,
 * and while a single display rounding would usually hide that, "usually" is not a
 * property this platform accepts anywhere near an amount. The backend stores minor
 * units as `BIGINT` for the same reason.
 *
 * Known limitation, and it is in JSON rather than here: a value beyond 2^53 has
 * already lost precision by the time `JSON.parse` produced it. `unsafe` is returned
 * for those instead of a confidently wrong figure — no realistic total reaches it,
 * and if one ever does, a visible marker beats a silent rounding.
 */
export function formatMoney(money: MoneyView | null | undefined): string {
  if (!money) {
    return ABSENT;
  }
  const currency = money.currency.toUpperCase();
  const symbol = SYMBOLS[currency] ?? `${currency} `;

  if (!Number.isFinite(money.amountMinor) || !Number.isSafeInteger(money.amountMinor)) {
    return `${symbol}?`;
  }

  const exponent = minorUnitExponent(currency);
  const scale = 10n ** BigInt(exponent);
  const minor = BigInt(money.amountMinor);
  const negative = minor < 0n;
  const absolute = negative ? -minor : minor;

  const whole = absolute / scale;
  const grouped = new Intl.NumberFormat(groupingLocale(currency)).format(whole);

  if (exponent === 0) {
    return `${negative ? '-' : ''}${symbol}${grouped}`;
  }

  const fraction = (absolute % scale).toString().padStart(exponent, '0');
  return `${negative ? '-' : ''}${symbol}${grouped}.${fraction}`;
}

/**
 * A compact amount for a chart axis, where four digits of paise do not fit.
 *
 * Only ever used on axis labels and never on a figure somebody would act on: this
 * rounds, and a rounded amount must not be mistaken for a balance.
 */
export function formatMoneyCompact(money: MoneyView | null | undefined): string {
  if (!money || !Number.isSafeInteger(money.amountMinor)) {
    return ABSENT;
  }
  const currency = money.currency.toUpperCase();
  const symbol = SYMBOLS[currency] ?? `${currency} `;
  const units = money.amountMinor / 10 ** minorUnitExponent(currency);
  const absolute = Math.abs(units);
  const sign = units < 0 ? '-' : '';

  // Crore and lakh for INR, because that is how the figures will be discussed by
  // the people reading them.
  if (currency === 'INR') {
    if (absolute >= 1e7) {
      return `${sign}${symbol}${(absolute / 1e7).toFixed(2)} Cr`;
    }
    if (absolute >= 1e5) {
      return `${sign}${symbol}${(absolute / 1e5).toFixed(2)} L`;
    }
  } else {
    if (absolute >= 1e9) {
      return `${sign}${symbol}${(absolute / 1e9).toFixed(2)}B`;
    }
    if (absolute >= 1e6) {
      return `${sign}${symbol}${(absolute / 1e6).toFixed(2)}M`;
    }
    if (absolute >= 1e3) {
      return `${sign}${symbol}${(absolute / 1e3).toFixed(1)}K`;
    }
  }
  return `${sign}${symbol}${absolute.toFixed(0)}`;
}

/** A plain count, grouped. Zero renders as `0`; absence renders as an em dash. */
export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return ABSENT;
  }
  return new Intl.NumberFormat('en-US').format(value);
}

/**
 * Formats a 0-1 rate as a percentage.
 *
 * Null returns an em dash rather than `0.0%`. The backend sends null for
 * `successRate` when nothing in the window reached a terminal state, and rendering
 * that as zero would tell an operator every payment failed on a morning when none
 * had finished yet.
 */
export function formatRate(rate: number | null | undefined, digits = 1): string {
  if (rate === null || rate === undefined || !Number.isFinite(rate)) {
    return ABSENT;
  }
  return `${(rate * 100).toFixed(digits)}%`;
}

/** Formats an already-0-100 score, such as `riskScore`. */
export function formatScore(score: number | null | undefined): string {
  if (score === null || score === undefined || !Number.isFinite(score)) {
    return ABSENT;
  }
  return String(Math.round(score));
}

/** Milliseconds, promoted to seconds once the digits stop being useful. */
export function formatMillis(millis: number | null | undefined): string {
  if (millis === null || millis === undefined || !Number.isFinite(millis)) {
    return ABSENT;
  }
  if (millis < 1000) {
    return `${Math.round(millis)} ms`;
  }
  if (millis < 60_000) {
    return `${(millis / 1000).toFixed(2)} s`;
  }
  return formatDuration(Math.round(millis / 1000));
}

/**
 * A duration in seconds, as the largest two units that carry information.
 *
 * `2h 14m` rather than `8040s`. This is used for how long a customer has been
 * unable to spend their own money, and "how bad is this" should be readable without
 * arithmetic.
 */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds)) {
    return ABSENT;
  }
  const total = Math.max(0, Math.round(seconds));
  if (total < 60) {
    return `${total}s`;
  }
  const days = Math.floor(total / 86_400);
  const hours = Math.floor((total % 86_400) / 3_600);
  const minutes = Math.floor((total % 3_600) / 60);

  if (days > 0) {
    return hours > 0 ? `${days}d ${hours}h` : `${days}d`;
  }
  if (hours > 0) {
    return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
  }
  return `${minutes}m`;
}

/**
 * An absolute timestamp in the operator's own timezone.
 *
 * The platform serialises every instant as UTC, and showing that raw would have
 * somebody comparing a UTC event time against the wall clock behind them during an
 * incident. `Intl.DateTimeFormat` converts using the browser's zone.
 */
export function formatDateTime(instant: IsoInstant | null | undefined): string {
  const date = parseInstant(instant);
  if (!date) {
    return ABSENT;
  }
  return new Intl.DateTimeFormat('en-GB', {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date);
}

/** Just the clock part, for a dense time-series axis. */
export function formatTimeOfDay(instant: IsoInstant | null | undefined): string {
  const date = parseInstant(instant);
  if (!date) {
    return ABSENT;
  }
  return new Intl.DateTimeFormat('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date);
}

export function formatDayAndTime(instant: IsoInstant | null | undefined): string {
  const date = parseInstant(instant);
  if (!date) {
    return ABSENT;
  }
  return new Intl.DateTimeFormat('en-GB', {
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date);
}

/**
 * How long ago, relative to a supplied `now`.
 *
 * `now` is a parameter rather than a call to `Date.now()` so the function stays
 * pure and a test can assert on a fixed clock. A helper that reads the wall clock
 * internally can only be tested by mocking time, which is a lot of machinery for
 * subtraction.
 */
export function formatAgo(
  instant: IsoInstant | null | undefined,
  now: number = Date.now(),
): string {
  const date = parseInstant(instant);
  if (!date) {
    return ABSENT;
  }
  const seconds = (now - date.getTime()) / 1000;
  // A negative age means the event is stamped in the future: clock skew between
  // services, or a machine with a wrong clock. Saying "just now" would hide a real
  // operational problem behind a friendly phrase.
  if (seconds < -60) {
    return `in ${formatDuration(-seconds)}`;
  }
  if (seconds < 10) {
    return 'just now';
  }
  return `${formatDuration(seconds)} ago`;
}

function parseInstant(instant: IsoInstant | null | undefined): Date | null {
  if (!instant) {
    return null;
  }
  const date = new Date(instant);
  // `new Date('nonsense')` yields an Invalid Date whose `toString` is
  // "Invalid Date" and whose arithmetic is NaN. Caught here so a malformed
  // timestamp shows as absent rather than propagating NaN into a chart.
  return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * Shortens a UUID for a table cell, keeping the leading characters.
 *
 * The prefix, not the suffix: it is what somebody types into a search box, and what
 * they will match against when the full id is copied out of a log.
 */
export function shortId(id: string | null | undefined, length = 8): string {
  if (!id) {
    return ABSENT;
  }
  return id.length <= length ? id : `${id.slice(0, length)}…`;
}

/** Turns `PARTIALLY_REFUNDED` into `Partially refunded` for prose contexts. */
export function humanise(value: string | null | undefined): string {
  if (!value) {
    return ABSENT;
  }
  const lower = value.replace(/_/g, ' ').toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}
