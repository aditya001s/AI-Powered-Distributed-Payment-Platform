import type { ReactElement } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { LoginPage } from './auth/LoginPage';
import { ProtectedRoute } from './auth/ProtectedRoute';
import { Layout } from './components/Layout';
import { AlertsPage } from './pages/AlertsPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { OverviewPage } from './pages/OverviewPage';
import { PaymentsPage } from './pages/PaymentsPage';
import { ProvidersPage } from './pages/ProvidersPage';
import { RiskPage } from './pages/RiskPage';

/**
 * Routes.
 *
 * Every route except `/login` sits behind `ProtectedRoute`, which is a usability gate
 * rather than a security one: the gateway restricts `/api/admin/**` to `ROLE_ADMIN` or
 * `ROLE_FRAUD_ANALYST` and each service checks again. What this achieves is that an
 * operator is never shown a page whose every request would come back 403.
 *
 * No per-route role requirements are set, because both roles that can sign in here can
 * read all six pages. The one capability that is genuinely admin-only — enabling and
 * disabling a payment rail — is gated on the button, where the reason can be shown,
 * rather than by hiding a page an analyst has a legitimate reason to read.
 */
export function App(): ReactElement {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />

        <Route element={<ProtectedRoute />}>
          <Route element={<Layout />}>
            <Route index element={<OverviewPage />} />
            <Route path="payments" element={<PaymentsPage />} />
            <Route path="risk" element={<RiskPage />} />
            <Route path="alerts" element={<AlertsPage />} />
            <Route path="providers" element={<ProvidersPage />} />
            <Route path="notifications" element={<NotificationsPage />} />
          </Route>
        </Route>

        {/* Anything else goes to the overview rather than a dead end. A mistyped or
            outdated bookmark should land somewhere useful. */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  );
}
