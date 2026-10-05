import { useMemo, useState } from 'react';
import type { ReactElement } from 'react';
import { ApiError } from '../api/client';
import { notificationApi } from '../api/endpoints';
import type { NotificationDeliveryView } from '../api/types';
import { DataTable, Pager, type Column } from '../components/DataTable';
import { KpiRow, KpiTile } from '../components/KpiTile';
import { PageHeader, Panel } from '../components/Layout';
import { formatCount, formatDateTime, humanise, shortId } from '../format';
import { describeError, useLoader } from '../hooks/useLoader';

const PAGE_SIZE = 20;

/**
 * Notifications that were never delivered.
 *
 * Every row is a customer the platform decided to tell something and then failed to
 * tell — a payment completed, a payment rejected, funds returned. The notification
 * service retries on its own persistent queue and only lands a delivery here once it
 * has exhausted every attempt, so nothing on this page is going to resolve itself.
 *
 * Replay is safe to press. The endpoint is idempotent server-side, so a double click
 * cannot send the same message twice — which matters, because the natural reaction to
 * a slow-looking button is to press it again.
 */
export function NotificationsPage(): ReactElement {
  const [page, setPage] = useState(0);
  const [pending, setPending] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const deadLetters = useLoader(
    (signal) => notificationApi.deadLetters({ page, size: PAGE_SIZE }, signal),
    [page],
  );
  const stats = useLoader((signal) => notificationApi.stats(signal), [], 60_000);

  async function replay(delivery: NotificationDeliveryView): Promise<void> {
    setPending(delivery.deliveryId);
    setError(null);
    setNote(null);
    try {
      await notificationApi.replay(delivery.deliveryId);
      setNote(
        `Queued ${shortId(delivery.deliveryId, 8)} for another attempt. It will be retried on the next sweep.`,
      );
      deadLetters.reload();
      stats.reload();
    } catch (caught) {
      if (caught instanceof ApiError && caught.isConflict) {
        setError('That delivery is no longer dead-lettered. Somebody may have replayed it already.');
      } else if (caught instanceof Error) {
        setError(describeError(caught));
      } else {
        setError('Could not queue the replay.');
      }
    } finally {
      setPending(null);
    }
  }

  const columns: ReadonlyArray<Column<NotificationDeliveryView>> = useMemo(
    () => [
      {
        key: 'delivery',
        header: 'Delivery',
        render: (row) => (
          <span className="cell-stack">
            <code title={row.deliveryId}>{shortId(row.deliveryId, 10)}</code>
            <span className="cell-sub">{row.channel}</span>
          </span>
        ),
      },
      {
        key: 'destination',
        header: 'Destination',
        // Masked by the server before it ever reaches this browser. Displayed as
        // received; unmasking it here would defeat the point of masking it there.
        render: (row) => <span className="cell-sub">{row.destination ?? 'not recorded'}</span>,
      },
      {
        key: 'status',
        header: 'Status',
        render: (row) => (
          <span className="cell-stack">
            <span className="badge badge--bad">{humanise(row.status)}</span>
            {row.suppressedReason ? (
              <span className="cell-sub">{row.suppressedReason}</span>
            ) : null}
          </span>
        ),
      },
      {
        key: 'attempts',
        header: 'Attempts',
        numeric: true,
        render: (row) => formatCount(row.attemptCount),
      },
      {
        key: 'error',
        header: 'Last error',
        secondary: true,
        render: (row) => (
          <span className="cell-summary">{row.lastError ?? 'none recorded'}</span>
        ),
      },
      {
        key: 'created',
        header: 'Created',
        render: (row) => (
          <span className="cell-stack">
            <span>{formatDateTime(row.createdAt)}</span>
            <span className="cell-sub">last change {formatDateTime(row.updatedAt)}</span>
          </span>
        ),
      },
      {
        key: 'actions',
        header: 'Action',
        render: (row) => (
          <button
            className="button button--quiet"
            type="button"
            disabled={pending === row.deliveryId}
            onClick={() => void replay(row)}
          >
            {pending === row.deliveryId ? 'Queueing…' : 'Replay'}
          </button>
        ),
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [pending],
  );

  const data = deadLetters.state.data;
  const statsData = stats.state.data;
  // The envelope from this module has no `totalPages` or `last`, so whether there is a
  // next page is inferred from the page size. Typing it as a `PageResponse` and reading
  // `last` would silently give `undefined`, and the Next button would never enable.
  const hasNext = data ? (data.page + 1) * data.size < data.totalElements : false;

  return (
    <div className="page">
      <PageHeader
        title="Notifications"
        description="Deliveries that exhausted every retry. Each one is a customer who was never told something the platform decided to tell them."
      >
        <button className="button button--quiet" type="button" onClick={deadLetters.reload}>
          {deadLetters.refreshing ? 'Refreshing…' : 'Refresh'}
        </button>
      </PageHeader>

      <KpiRow>
        <KpiTile
          label="Dead-lettered (24h)"
          value={formatCount(statsData?.deadLettered24h)}
          tone={(statsData?.deadLettered24h ?? 0) > 0 ? 'warn' : 'good'}
          hint="Gave up in the last day."
        />
        <KpiTile
          label="Dead-lettered (total)"
          value={formatCount(statsData?.deadLetteredTotal)}
          hint="Everything still waiting to be dealt with."
        />
        <KpiTile
          label="Retry backlog"
          value={formatCount(statsData?.retryBacklog)}
          // Still being retried, so not yet a failure. A backlog that keeps growing is
          // a channel that is down rather than flaky.
          hint="Still inside the retry window. Not failures yet."
        />
        <KpiTile
          label="Notifications (24h)"
          value={formatCount(statsData?.notifications24h)}
          hint="Raised in the last day."
        />
        <KpiTile
          label="Deliveries (24h)"
          value={formatCount(statsData?.deliveries24h)}
          hint="One notification can produce several, one per channel."
        />
      </KpiRow>

      {note ? (
        <p className="notice notice--good" role="status">
          {note}
        </p>
      ) : null}
      {error ? (
        <p className="notice notice--bad" role="alert">
          {error}
        </p>
      ) : null}

      <Panel
        title="Dead letters"
        description="Replay is idempotent — pressing it twice cannot send the message twice."
      >
        <DataTable
          caption="Notification deliveries that exhausted every retry"
          columns={columns}
          rows={data?.content ?? []}
          rowKey={(row) => row.deliveryId}
          loading={deadLetters.state.status === 'loading'}
          error={
            deadLetters.state.status === 'failed' && !data
              ? describeError(deadLetters.state.error)
              : null
          }
          onRetry={deadLetters.reload}
          empty="Nothing has been dead-lettered. Every notification the platform raised was delivered or deliberately suppressed."
        />

        {data ? (
          <Pager
            page={data.page}
            size={data.size}
            totalElements={data.totalElements}
            hasNext={hasNext}
            onPage={setPage}
            busy={deadLetters.refreshing}
          />
        ) : null}
      </Panel>

      <p className="page__footnote">
        High-priority notifications ignore channel preferences by design: a customer cannot opt out
        of being told their payment was rejected. Suppressed deliveries are a preference being
        honoured, not a failure, and are not listed here.
      </p>
    </div>
  );
}
