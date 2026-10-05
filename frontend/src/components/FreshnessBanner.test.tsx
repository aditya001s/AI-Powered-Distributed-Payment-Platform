import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { FreshnessBanner, FreshnessNote } from './FreshnessBanner';
import type { FreshnessView } from '../api/types';

/**
 * The staleness banner.
 *
 * <p>This component exists because a projection that stops updating does not go blank. It keeps serving the
 * last figures it managed to build, confidently and indefinitely. Without the banner, "no held payments" and
 * "the consumer died three hours ago" are the same screen, and they call for opposite responses.
 *
 * <p>So the tests below are really about three states staying distinguishable: current, behind, and never
 * populated. The third is the one most likely to be collapsed into one of the others by a well-meaning
 * simplification.
 */

const NOW = Date.parse('2026-03-01T12:00:00Z');

function freshness(overrides: Partial<FreshnessView> = {}): FreshnessView {
  return {
    newestEventAt: '2026-03-01T11:59:30Z',
    lagSeconds: 30,
    stale: false,
    streams: [
      {
        topic: 'payment-created',
        lastEventAt: '2026-03-01T11:59:30Z',
        lastAppliedAt: '2026-03-01T11:59:31Z',
        lagSeconds: 30,
        stale: false,
      },
      {
        topic: 'fraud-checked',
        lastEventAt: '2026-03-01T11:59:20Z',
        lastAppliedAt: '2026-03-01T11:59:21Z',
        lagSeconds: 40,
        stale: false,
      },
    ],
    ...overrides,
  };
}

describe('FreshnessBanner', () => {
  it('says the projection is current when it is', () => {
    render(<FreshnessBanner freshness={freshness()} now={NOW} />);

    expect(screen.getByText('Projection is current.')).toBeInTheDocument();
    expect(screen.getByText(/lag 30s/)).toBeInTheDocument();
  });

  /**
   * A stale projection is announced as an alert rather than a status, because acting on stale figures can be
   * wrong. A healthy one is a status and must not interrupt anybody.
   */
  it('announces staleness as an alert, not a quiet status', () => {
    render(
      <FreshnessBanner
        freshness={freshness({
          stale: true,
          lagSeconds: 5_400,
          newestEventAt: '2026-03-01T10:30:00Z',
        })}
        now={NOW}
      />,
    );

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Figures may be out of date.');
    expect(alert).toHaveTextContent('1h 30m');
  });

  /**
   * Per stream, not one global figure. A single number only looks stale once every consumer has stopped; if
   * the `fraud-checked` listener alone dies, an overall lag stays healthy on the strength of the other six
   * while every risk figure silently decays.
   */
  it('names the streams that are behind', () => {
    render(
      <FreshnessBanner
        freshness={freshness({
          stale: true,
          streams: [
            {
              topic: 'payment-created',
              lastEventAt: '2026-03-01T11:59:30Z',
              lastAppliedAt: '2026-03-01T11:59:31Z',
              lagSeconds: 30,
              stale: false,
            },
            {
              topic: 'fraud-checked',
              lastEventAt: '2026-03-01T09:00:00Z',
              lastAppliedAt: '2026-03-01T09:00:01Z',
              lagSeconds: 10_800,
              stale: true,
            },
          ],
        })}
        now={NOW}
      />,
    );

    expect(screen.getByText(/1 of 2 streams behind/)).toBeInTheDocument();
    expect(screen.getByText(/fraud-checked/)).toBeInTheDocument();
  });

  it('lists every stream with its lag when expanded', async () => {
    const user = userEvent.setup();
    render(<FreshnessBanner freshness={freshness()} now={NOW} />);

    await user.click(screen.getByRole('button', { name: 'Show streams' }));

    const table = screen.getByRole('table');
    expect(table).toHaveTextContent('payment-created');
    expect(table).toHaveTextContent('fraud-checked');
    expect(screen.getByRole('button', { name: 'Hide streams' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
  });

  /**
   * <strong>The third state.</strong> Nothing projected at all is normal on a new deployment and the most
   * severe thing this banner can report on an established one. It says which situation it cannot tell apart
   * rather than picking one and being wrong half the time.
   */
  it('treats an empty projection as its own case and says what it cannot distinguish', () => {
    render(
      <FreshnessBanner
        freshness={freshness({ newestEventAt: null, lagSeconds: null, streams: [] })}
        now={NOW}
      />,
    );

    expect(screen.getByText('No events projected yet.')).toBeInTheDocument();
    expect(screen.getByText(/new deployment/)).toBeInTheDocument();
    expect(screen.getByText(/consumers have never run/)).toBeInTheDocument();
    // Not reported as stale, which would send somebody hunting a broken consumer on their first morning.
    expect(screen.queryByText('Figures may be out of date.')).not.toBeInTheDocument();
  });

  /** A null lag must not render as "0s", which reads as perfectly current. */
  it('does not render a null lag as zero', () => {
    render(
      <FreshnessBanner
        freshness={freshness({ lagSeconds: null, newestEventAt: '2026-03-01T11:00:00Z' })}
        now={NOW}
      />,
    );

    expect(screen.queryByText(/lag 0s/)).not.toBeInTheDocument();
  });
});

describe('FreshnessNote', () => {
  it('reports the lag in one line', () => {
    render(<FreshnessNote freshness={freshness()} />);

    expect(screen.getByText('Projection lag 30s')).toBeInTheDocument();
  });

  it('marks staleness in the compact form too', () => {
    render(<FreshnessNote freshness={freshness({ stale: true, lagSeconds: 600 })} />);

    expect(screen.getByText(/stale/)).toBeInTheDocument();
  });

  /** An em dash rather than a reassuring "0s" when nothing has been projected. */
  it('shows absence rather than zero for an empty projection', () => {
    render(<FreshnessNote freshness={freshness({ newestEventAt: null, lagSeconds: null })} />);

    expect(screen.getByText('Projection: —')).toBeInTheDocument();
  });
});
