import { useState } from 'react';
import type { FormEvent, ReactElement } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { ApiError } from '../api/client';
import { useAuth } from './AuthContext';

interface RedirectState {
  from?: { pathname?: string };
}

/**
 * Sign-in.
 *
 * Deliberately says as little as possible about *why* a sign-in failed. The auth
 * service already answers a wrong password and an unknown email identically; the
 * UI must not undo that by distinguishing them, or the form becomes a way to
 * enumerate which addresses have accounts.
 */
export function LoginPage(): ReactElement {
  const { status, login, expired } = useAuth();
  const location = useLocation();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (status === 'authenticated') {
    const state = location.state as RedirectState | null;
    return <Navigate to={state?.from?.pathname ?? '/'} replace />;
  }

  async function onSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email.trim(), password);
    } catch (caught) {
      if (caught instanceof ApiError) {
        if (caught.isRateLimited) {
          // The gateway limits this path by IP at 3 requests with a burst of 6.
          // Saying so turns a mysterious failure into a wait.
          setError('Too many attempts. Wait a moment and try again.');
        } else if (caught.status === 401 || caught.status === 400) {
          setError('Those credentials were not accepted.');
        } else if (caught.status === 403) {
          // A locked or suspended account. Distinct from bad credentials because
          // the remedy is different and the account already exists either way.
          setError('This account cannot sign in. Contact an administrator.');
        } else {
          setError(
            caught.correlationId
              ? `Sign-in failed (${caught.code}). Reference: ${caught.correlationId}`
              : `Sign-in failed (${caught.code}).`,
          );
        }
      } else {
        // Not an ApiError, so the request never reached the gateway. Naming that
        // distinction saves somebody checking their password when the platform is
        // simply not running.
        setError('Could not reach the platform. Check that the gateway is running.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="login">
      <form className="login__card" onSubmit={onSubmit} noValidate>
        <h1 className="login__title">AegisPay</h1>
        <p className="login__subtitle">Operations and fraud console</p>

        {expired ? (
          <p className="login__notice" role="status">
            Your session ended. Sign in again to continue.
          </p>
        ) : null}

        <label className="field" htmlFor="login-email">
          <span className="field__label">Email</span>
          <input
            id="login-email"
            name="email"
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
        </label>

        <label className="field" htmlFor="login-password">
          <span className="field__label">Password</span>
          <input
            id="login-password"
            name="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </label>

        {/*
          role="alert" so a screen reader announces the failure. A silently
          appearing error message is invisible to anybody not watching that
          region of the page.
        */}
        {error ? (
          <p className="login__error" role="alert">
            {error}
          </p>
        ) : null}

        <button
          className="button button--primary"
          type="submit"
          disabled={submitting || email.trim().length === 0 || password.length === 0}
        >
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>

        <p className="login__hint">
          Access needs the <code>ADMIN</code> or <code>FRAUD_ANALYST</code> role.
        </p>
      </form>
    </main>
  );
}
