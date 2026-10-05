import { useState } from 'react';
import type { ReactElement } from 'react';
import type { FreshnessView } from '../api/types';
import { ABSENT, formatAgo, formatDuration } from '../format';

/**
 * States how old the projected numbers on this page are.
 *
 * Rendered on every page that shows a figure derived from the read model, and that
 * is not belt-and-braces. A Kafka consumer that stops does not blank the dashboard —
 * the projection keeps serving the last figures it managed to build, confidently and
 * indefinitely. Without this banner, "no held payments" and "the consumer died three
 * hours ago" are the same screen, and they call for opposite responses.
 *
 * Per-stream rather than one global number, because the backend reports it per
 * stream. A single aggregate figure only looks stale once *every* consumer has
 * stopped; if the `payment-completed` listener alone dies, the overall lag stays
 * healthy on the strength of the other six while the success rate silently decays.
 */
export function FreshnessBanner({
  freshness,
  /** Passed in so the component stays deterministic under test. */
  now = Date.now(),
}: {
  freshness: FreshnessView;
  now?: number;
}): ReactElement {
  const [expanded, setExpanded] = useState(false);

  const staleStreams = freshness.streams.filter((stream) => stream.stale);
  const nothingProjected = freshness.newestEventAt === null;

  /*
   * Nothing projected at all is its own case.
   *
   * On a new deployment it is entirely normal and calling it "stale" would have
   * somebody hunting a broken consumer on their first morning. It is also, on an
   * established deployment, the most severe thing this banner can report — so it
   * says which situation it cannot distinguish rather than picking one.
   */
  if (nothingProjected) {
    return (
      <div className="freshness freshness--empty" role="status">
        <strong>No events projected yet.</strong>{' '}
        <span>
          Every figure below will read zero. Expected on a new deployment; on an
          established one it means the consumers have never run.
        </span>
      </div>
    );
  }

  const tone = freshness.stale ? 'freshness--stale' : 'freshness--live';

  return (
    <div
      className={`freshness ${tone}`}
      // A stale projection is an alert, because acting on it can be wrong. A healthy
      // one is a status and should not interrupt anybody.
      role={freshness.stale ? 'alert' : 'status'}
    >
      <div className="freshness__summary">
        {freshness.stale ? (
          <strong>Figures may be out of date.</strong>
        ) : (
          <strong>Projection is current.</strong>
        )}
        <span>
          Newest event {formatAgo(freshness.newestEventAt, now)}
          {freshness.lagSeconds === null ? '' : ` · lag ${formatDuration(freshness.lagSeconds)}`}
        </span>
        {staleStreams.length > 0 ? (
          <span className="freshness__streams">
            {staleStreams.length} of {freshness.streams.length} streams behind:{' '}
            {staleStreams.map((stream) => stream.topic).join(', ')}
          </span>
        ) : null}
        <button
          className="button button--quiet"
          type="button"
          onClick={() => setExpanded((value) => !value)}
          aria-expanded={expanded}
        >
          {expanded ? 'Hide streams' : 'Show streams'}
        </button>
      </div>

      {expanded ? (
        <table className="freshness__table">
          <caption className="visually-hidden">Projection lag by Kafka topic</caption>
          <thead>
            <tr>
              <th scope="col">Topic</th>
              <th scope="col">Last event</th>
              <th scope="col">Lag</th>
            </tr>
          </thead>
          <tbody>
            {freshness.streams.length === 0 ? (
              <tr>
                <td colSpan={3}>No streams have delivered anything.</td>
              </tr>
            ) : (
              freshness.streams.map((stream) => (
                <tr key={stream.topic} className={stream.stale ? 'freshness__row--stale' : ''}>
                  <td>
                    <code>{stream.topic}</code>
                  </td>
                  <td>{formatAgo(stream.lastEventAt, now)}</td>
                  <td>
                    {formatDuration(stream.lagSeconds)}
                    {stream.stale ? ' (behind)' : ''}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      ) : null}
    </div>
  );
}

/**
 * A compact freshness note, for pages where the banner would dominate.
 *
 * Reports the same fact in one line. `ABSENT` when nothing has been projected, rather
 * than a reassuring "0s".
 */
export function FreshnessNote({ freshness }: { freshness: FreshnessView }): ReactElement {
  // No clock needed: `lagSeconds` is computed server-side against the same clock that
  // decided `stale`. Recomputing it here from the browser's clock would disagree with
  // the server's own verdict whenever the two machines differ, and the server's is the
  // one that matters.
  return (
    <span className={`freshness-note ${freshness.stale ? 'freshness-note--stale' : ''}`}>
      {freshness.newestEventAt === null
        ? `Projection: ${ABSENT}`
        : `Projection lag ${formatDuration(freshness.lagSeconds)}${freshness.stale ? ' — stale' : ''}`}
    </span>
  );
}
