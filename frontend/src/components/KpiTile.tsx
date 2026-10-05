import type { ReactElement, ReactNode } from 'react';
import { ABSENT } from '../format';

/**
 * One headline figure.
 *
 * The `hint` is not decoration. Several of these numbers are easy to misread —
 * "degraded verdicts" sounds like a warning count rather than a count of payments
 * assessed without the model — and a tile with no explanation invites the reader to
 * supply their own. Where the meaning is subtle, the tile says it.
 */
export function KpiTile({
  label,
  value,
  hint,
  tone = 'neutral',
  emphasis,
}: {
  label: string;
  /** Pre-formatted. Formatting happens in `format.ts`, which is tested. */
  value: string;
  hint?: string;
  tone?: 'neutral' | 'good' | 'warn' | 'bad';
  /** A secondary line, such as a comparison or an amount alongside a count. */
  emphasis?: ReactNode;
}): ReactElement {
  /*
   * An em dash means the platform genuinely has no value, not zero. Toning that
   * as good or bad would assert something about a number that does not exist —
   * a null success rate rendered in green reads as "100%".
   */
  const effectiveTone = value === ABSENT ? 'neutral' : tone;

  return (
    <div className={`kpi kpi--${effectiveTone}`}>
      <span className="kpi__label">{label}</span>
      <span className="kpi__value">{value}</span>
      {emphasis ? <span className="kpi__emphasis">{emphasis}</span> : null}
      {hint ? <span className="kpi__hint">{hint}</span> : null}
    </div>
  );
}

/** A row of tiles. Wraps rather than scrolling: a hidden tile is a tile nobody reads. */
export function KpiRow({ children }: { children: ReactNode }): ReactElement {
  return <div className="kpi-row">{children}</div>;
}
