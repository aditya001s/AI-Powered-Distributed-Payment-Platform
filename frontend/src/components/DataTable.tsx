import type { ReactElement, ReactNode } from 'react';

/**
 * A table, with the three states a table needs to tell apart.
 *
 * "Loading", "failed" and "genuinely empty" look identical if a component just maps
 * over an array. On this dashboard that confusion is expensive: an empty alert queue
 * means there is nothing to do, while a failed one means the queue is unknown and
 * customers may be waiting. They must never render the same way.
 */

export interface Column<T> {
  key: string;
  header: string;
  render: (row: T) => ReactNode;
  /** Right-aligns the column. Numbers are read by comparing digit positions. */
  numeric?: boolean;
  /** Hidden on narrow viewports, for columns that are useful but not essential. */
  secondary?: boolean;
}

export function DataTable<T>({
  columns,
  rows,
  rowKey,
  caption,
  empty = 'Nothing to show.',
  loading = false,
  error,
  onRetry,
  rowClassName,
}: {
  columns: ReadonlyArray<Column<T>>;
  rows: ReadonlyArray<T>;
  rowKey: (row: T) => string;
  /** Describes the table to a screen reader. Required, not optional. */
  caption: string;
  empty?: string;
  loading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  rowClassName?: (row: T) => string | undefined;
}): ReactElement {
  const visible = columns;

  return (
    <div className="table-wrap">
      <table className="table">
        {/* Visually hidden, but it is what tells a screen reader user which of
            several tables on the page they have landed in. */}
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            {visible.map((column) => (
              <th
                key={column.key}
                scope="col"
                className={[
                  column.numeric ? 'table__cell--numeric' : '',
                  column.secondary ? 'table__cell--secondary' : '',
                ]
                  .filter(Boolean)
                  .join(' ')}
              >
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {error ? (
            <tr>
              <td className="table__state table__state--error" colSpan={visible.length}>
                <span role="alert">{error}</span>
                {onRetry ? (
                  <button className="button button--quiet" type="button" onClick={onRetry}>
                    Retry
                  </button>
                ) : null}
              </td>
            </tr>
          ) : loading ? (
            <tr>
              <td className="table__state" colSpan={visible.length}>
                <span role="status">Loading…</span>
              </td>
            </tr>
          ) : rows.length === 0 ? (
            <tr>
              <td className="table__state" colSpan={visible.length}>
                {empty}
              </td>
            </tr>
          ) : (
            rows.map((row) => (
              <tr key={rowKey(row)} className={rowClassName?.(row)}>
                {visible.map((column) => (
                  <td
                    key={column.key}
                    className={[
                      column.numeric ? 'table__cell--numeric' : '',
                      column.secondary ? 'table__cell--secondary' : '',
                    ]
                      .filter(Boolean)
                      .join(' ')}
                  >
                    {column.render(row)}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Page controls for a server-paged list.
 *
 * Shows the element count because the totals on this dashboard are the point: "1-20
 * of 4,812 payments" tells an operator whether a filter did anything, which
 * "Next/Previous" alone does not.
 */
export function Pager({
  page,
  size,
  totalElements,
  hasNext,
  onPage,
  busy = false,
}: {
  /** Zero-based, matching Spring's `Pageable`. Displayed one-based. */
  page: number;
  size: number;
  totalElements: number;
  hasNext: boolean;
  onPage: (page: number) => void;
  busy?: boolean;
}): ReactElement | null {
  if (totalElements === 0) {
    return null;
  }
  const first = page * size + 1;
  const last = Math.min(totalElements, (page + 1) * size);

  return (
    <div className="pager">
      <span className="pager__range" aria-live="polite">
        {first.toLocaleString('en-US')}–{last.toLocaleString('en-US')} of{' '}
        {totalElements.toLocaleString('en-US')}
      </span>
      <button
        className="button button--quiet"
        type="button"
        onClick={() => onPage(page - 1)}
        disabled={busy || page === 0}
      >
        Previous
      </button>
      <button
        className="button button--quiet"
        type="button"
        onClick={() => onPage(page + 1)}
        disabled={busy || !hasNext}
      >
        Next
      </button>
    </div>
  );
}
