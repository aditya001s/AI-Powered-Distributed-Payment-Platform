import type { ReactElement, ReactNode } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

interface NavItem {
  to: string;
  label: string;
  /** Roles that may see this entry. Omitted means any signed-in operator. */
  roles?: string[];
  hint: string;
}

/**
 * Navigation, filtered by role.
 *
 * Filtering is for usability only. Every one of these routes calls an endpoint the
 * gateway independently restricts to `ROLE_ADMIN` or `ROLE_FRAUD_ANALYST`, and the
 * owning service checks again. Hiding a link stops somebody being shown a page that
 * would answer every request with a 403; it is not what keeps them out.
 */
const NAV: ReadonlyArray<NavItem> = [
  { to: '/', label: 'Overview', hint: 'Platform totals and throughput' },
  { to: '/payments', label: 'Payments', hint: 'Every payment, across all customers' },
  { to: '/risk', label: 'Risk', hint: 'Score distribution and risk bands' },
  { to: '/alerts', label: 'Alerts', hint: 'Held payments awaiting a decision' },
  {
    to: '/providers',
    label: 'Providers',
    // Visible to analysts because provider health explains a spike in failures they
    // will be asked about. Only an ADMIN can act on it, and the buttons say so.
    hint: 'Rail health and routing',
  },
  {
    to: '/notifications',
    label: 'Notifications',
    hint: 'Undelivered customer notifications',
  },
];

export function Layout(): ReactElement {
  const { user, logout, hasRole, isAdmin, isAnalyst } = useAuth();

  const visible = NAV.filter((item) => !item.roles || hasRole(...item.roles));

  return (
    <div className="shell">
      <header className="shell__header">
        <div className="shell__brand">
          <span className="shell__logo" aria-hidden>
            ◈
          </span>
          <span>
            AegisPay <span className="shell__brand-sub">operations</span>
          </span>
        </div>

        {/* A single landmark named for what it contains, so a screen reader user can
            jump straight to it rather than tabbing through the header. */}
        <nav className="shell__nav" aria-label="Sections">
          <ul>
            {visible.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  // `end` on the root only. Without it the overview link stays
                  // highlighted on every page, since every path starts with "/".
                  end={item.to === '/'}
                  className={({ isActive }) =>
                    isActive ? 'shell__link shell__link--active' : 'shell__link'
                  }
                  title={item.hint}
                >
                  {item.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>

        <div className="shell__identity">
          <span className="shell__user">
            {user?.fullName ?? user?.email ?? 'Signed in'}
            {/* The role is shown because it determines which actions will work.
                An analyst clicking a disable button and getting a 403 should have
                been able to see why beforehand. */}
            <span className="shell__role">
              {isAdmin ? 'Admin' : isAnalyst ? 'Fraud analyst' : 'Operator'}
            </span>
          </span>
          <button className="button button--quiet" type="button" onClick={() => void logout()}>
            Sign out
          </button>
        </div>
      </header>

      <main className="shell__main">
        <Outlet />
      </main>

      <footer className="shell__footer">
        <span>
          Reads go through the API gateway. Actions are attributed to your own account and recorded.
        </span>
      </footer>
    </div>
  );
}

/** A page heading with an optional description and controls on the right. */
export function PageHeader({
  title,
  description,
  children,
}: {
  title: string;
  description?: ReactNode;
  children?: ReactNode;
}): ReactElement {
  return (
    <div className="page-header">
      <div>
        <h1 className="page-header__title">{title}</h1>
        {description ? <p className="page-header__description">{description}</p> : null}
      </div>
      {children ? <div className="page-header__controls">{children}</div> : null}
    </div>
  );
}

/** A grouping box with a heading, for a section of a page. */
export function Panel({
  title,
  description,
  children,
  actions,
}: {
  title: string;
  description?: ReactNode;
  children: ReactNode;
  actions?: ReactNode;
}): ReactElement {
  return (
    <section className="panel">
      <div className="panel__head">
        <div>
          <h2 className="panel__title">{title}</h2>
          {description ? <p className="panel__description">{description}</p> : null}
        </div>
        {actions ? <div className="panel__actions">{actions}</div> : null}
      </div>
      {children}
    </section>
  );
}

/**
 * The failed-to-load state for a whole panel.
 *
 * Always offers a retry. Most failures here are a service restarting or a rate limit,
 * both of which clear on their own, and forcing a full page reload to find that out
 * loses whatever filters the operator had set.
 */
export function LoadError({
  message,
  onRetry,
}: {
  message: string;
  onRetry?: () => void;
}): ReactElement {
  return (
    <div className="load-error" role="alert">
      <span>{message}</span>
      {onRetry ? (
        <button className="button button--quiet" type="button" onClick={onRetry}>
          Retry
        </button>
      ) : null}
    </div>
  );
}

export function Loading({ what }: { what: string }): ReactElement {
  return (
    <div className="loading" role="status" aria-live="polite">
      Loading {what}…
    </div>
  );
}
