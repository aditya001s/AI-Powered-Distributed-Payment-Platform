import { Navigate, Outlet, useLocation } from 'react-router-dom';
import type { ReactElement } from 'react';
import { useAuth } from './AuthContext';

/**
 * Gate for every route that is not the login page.
 *
 * This is a usability boundary, not a security boundary. The real check happens at
 * the gateway, which refuses `/api/admin/**` without `ROLE_ADMIN` or
 * `ROLE_FRAUD_ANALYST`, and again in each service. Hiding a route here only stops
 * somebody being shown a page that would answer every request with a 403 — the
 * data itself is never protected by what the browser chooses to render.
 */
export function ProtectedRoute({ requireRole }: { requireRole?: string[] }): ReactElement {
  const { status, hasRole } = useAuth();
  const location = useLocation();

  /*
   * A stored refresh token is still being exchanged.
   *
   * Redirecting during this window is the bug worth avoiding: a reload would bounce
   * an already-signed-in operator to the login page for the fraction of a second
   * the refresh takes, and any deep link they followed would be lost.
   */
  if (status === 'restoring') {
    return (
      <div className="route-gate" role="status" aria-live="polite">
        Restoring session…
      </div>
    );
  }

  if (status === 'anonymous') {
    // The attempted location travels with the redirect so signing in returns the
    // operator to the alert they were sent a link to, not to the overview.
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  if (requireRole && !hasRole(...requireRole)) {
    return (
      <div className="route-gate route-gate--denied" role="alert">
        <h2>Not available to your role</h2>
        <p>
          This view needs one of: {requireRole.join(', ')}. Ask an administrator if you need access.
        </p>
      </div>
    );
  }

  return <Outlet />;
}
