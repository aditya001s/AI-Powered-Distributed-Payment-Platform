import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import type { AlertView, AnalystDecisionView, AssessmentDetailView, HeldPaymentView, PageResponse } from '../api/types';
import { AlertsPage } from './AlertsPage';

/**
 * The approve/block flow — the only place this dashboard changes anything.
 *
 * <p>Everything asserted below is about a decision being recorded correctly, or refused safely:
 *
 * <ul>
 *   <li><strong>A reason is mandatory.</strong> It is the only record of why a human overrode the engine, and
 *       somebody reading it in six months — an auditor, or whoever handles the customer's complaint — has
 *       nothing else to go on. The server enforces it; the UI must not let a request go without it.</li>
 *   <li><strong>Claim before deciding.</strong> `take` runs first, so two analysts working the same queue
 *       collide at the point of starting work rather than after one has typed a paragraph of justification.</li>
 *   <li><strong>A 409 says nothing was changed.</strong> Losing the race is not a failure to report
 *       ambiguously; the operator has to know the other decision stands and theirs did not apply.</li>
 *   <li><strong>The success message must not overclaim.</strong> The endpoint returns as soon as the decision
 *       is durable. The payment resumes asynchronously, so claiming it completed would be a guess — and an
 *       operator who believed it would stop watching for the case where it does not.</li>
 * </ul>
 */

vi.mock('../api/endpoints', () => ({
  fraudApi: {
    alerts: vi.fn(),
    take: vi.fn(),
    decide: vi.fn(),
    assessmentDetail: vi.fn(),
  },
  orchestrationApi: {
    held: vi.fn(),
  },
}));

const { fraudApi, orchestrationApi } = await import('../api/endpoints');

const ALERT: AlertView = {
  alertId: 'ALR-0000000000000001',
  paymentId: 'PAY-0000000000000001',
  assessmentId: 'ASM-0000000000000001',
  initiatorUserId: 'USR-0000000000000001',
  riskLevel: 'HIGH',
  riskScore: 82,
  decision: 'HOLD',
  summary: 'Velocity above this customer’s baseline and a new beneficiary',
  status: 'OPEN',
  assignedTo: null,
  blocksPayment: true,
  createdAt: '2026-03-01T10:00:00Z',
  resolvedAt: null,
};

const HELD: HeldPaymentView = {
  sagaId: 'SAGA-1',
  paymentId: ALERT.paymentId,
  initiatorUserId: ALERT.initiatorUserId,
  amount: { amount: '20000.00', amountMinor: 2_000_000, currency: 'INR' },
  riskLevel: 'HIGH',
  fraudDecision: 'HOLD',
  fraudProbability: 0.82,
  riskAssessmentId: ALERT.assessmentId,
  riskDegraded: false,
  holdId: 'HOLD-1',
  riskHeldAt: '2026-03-01T10:00:00Z',
  waitingSeconds: 5_400,
};

const DETAIL: AssessmentDetailView = {
  assessment: {
    assessmentId: ALERT.assessmentId,
    paymentId: ALERT.paymentId,
    transactionId: 'TXN-1',
    fraudProbability: 0.82,
    riskScore: 82,
    riskLevel: 'HIGH',
    decision: 'HOLD',
    amount: HELD.amount,
    reasons: ['Velocity above baseline', 'Beneficiary first seen today'],
    ruleHits: ['RULE_VELOCITY', 'RULE_NEW_BENEFICIARY'],
    modelVersion: 'fraud_lgbm-20260901T104500Z',
    scoringLatencyMs: 24,
    degradedMode: false,
    evaluatedAt: '2026-03-01T10:00:01Z',
  },
  featureSnapshot: '{"amount":20000}',
  decisions: [],
};

const DECISION: AnalystDecisionView = {
  decisionId: 'DEC-0000000000000001',
  alertId: ALERT.alertId,
  paymentId: ALERT.paymentId,
  action: 'RELEASE',
  reason: 'Spoke to the customer; the beneficiary is their new landlord.',
  decidedBy: 'USR-ANALYST-1',
  decidedAt: '2026-03-01T11:30:00Z',
};

function page(content: AlertView[]): PageResponse<AlertView> {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: 1,
    first: true,
    last: true,
  };
}

function conflict(message: string): ApiError {
  return new ApiError(409, { status: 409, code: 'CONFLICT', message });
}

beforeEach(() => {
  vi.mocked(fraudApi.alerts).mockResolvedValue(page([ALERT]));
  vi.mocked(orchestrationApi.held).mockResolvedValue([HELD]);
  vi.mocked(fraudApi.assessmentDetail).mockResolvedValue(DETAIL);
  vi.mocked(fraudApi.take).mockResolvedValue({ ...ALERT, status: 'IN_REVIEW' });
  vi.mocked(fraudApi.decide).mockResolvedValue(DECISION);
});

/** Opens the review dialog for the single alert in the fixture. */
async function openReview(): Promise<ReturnType<typeof userEvent.setup>> {
  const user = userEvent.setup();
  render(<AlertsPage />);

  await user.click(await screen.findByRole('button', { name: 'Review' }));
  await screen.findByRole('dialog');
  return user;
}

describe('the queue', () => {
  it('requests blocking alerts oldest first', async () => {
    render(<AlertsPage />);

    await waitFor(() => expect(fraudApi.alerts).toHaveBeenCalled());

    expect(fraudApi.alerts).toHaveBeenCalledWith(
      expect.objectContaining({
        // Sent explicitly, not omitted: to the fraud service, blockingOnly=false and an absent
        // filter are different queries.
        blockingOnly: true,
        status: 'OPEN',
        // Oldest first. A queue worked newest-first leaves the customer who has waited longest
        // waiting longer still.
        sort: 'createdAt,asc',
      }),
      expect.anything(),
    );
  });

  it('shows the risk, the amount and how long the funds have been reserved', async () => {
    render(<AlertsPage />);

    const row = (await screen.findByText(/PAY-0000/)).closest('tr');
    expect(row).not.toBeNull();
    expect(within(row as HTMLElement).getByText('High 82')).toBeInTheDocument();
    expect(within(row as HTMLElement).getByText('₹20,000.00')).toBeInTheDocument();
    // 5,400 seconds. This is how long the customer has been unable to spend their own money.
    expect(within(row as HTMLElement).getByText('1h 30m')).toBeInTheDocument();
    expect(within(row as HTMLElement).getByText('Blocking')).toBeInTheDocument();
  });

  /**
   * An empty queue must read as "nothing to do", not as a table that failed to load. Those two states call
   * for opposite responses.
   */
  it('says explicitly when nothing is being held', async () => {
    vi.mocked(fraudApi.alerts).mockResolvedValue(page([]));
    render(<AlertsPage />);

    expect(
      await screen.findByText('No payments are being held. Nothing needs a decision.'),
    ).toBeInTheDocument();
  });

  /**
   * A failed secondary read degrades rather than blanking the page: the queue is still workable without the
   * waiting times, and hiding it would be worse.
   */
  it('keeps the queue usable when the waiting times cannot be loaded', async () => {
    vi.mocked(orchestrationApi.held).mockRejectedValue(
      new ApiError(503, { status: 503, code: 'UNAVAILABLE', message: 'orchestrator down' }),
    );
    render(<AlertsPage />);

    expect(await screen.findByText(/Waiting times unavailable/)).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Review' })).toBeInTheDocument();
  });
});

describe('the review dialog', () => {
  it('shows the evidence before the buttons', async () => {
    await openReview();

    expect(await screen.findByText('Velocity above baseline')).toBeInTheDocument();
    expect(screen.getByText('RULE_VELOCITY')).toBeInTheDocument();
    expect(screen.getByText('fraud_lgbm-20260901T104500Z')).toBeInTheDocument();
  });

  /**
   * Null in degraded mode: nothing was scored, so there is no model to name. Printing a stale version would
   * misattribute a rules-only verdict to the model.
   */
  it('says no model was used when the verdict was degraded', async () => {
    vi.mocked(fraudApi.assessmentDetail).mockResolvedValue({
      ...DETAIL,
      assessment: { ...DETAIL.assessment, modelVersion: null, degradedMode: true },
    });

    await openReview();

    expect(await screen.findByText('none — rules only')).toBeInTheDocument();
    expect(screen.getByText('Degraded')).toBeInTheDocument();
  });

  it('shows earlier decisions on the alert', async () => {
    vi.mocked(fraudApi.assessmentDetail).mockResolvedValue({
      ...DETAIL,
      decisions: [{ ...DECISION, action: 'RELEASE', reason: 'Previously released in error' }],
    });

    await openReview();

    expect(await screen.findByText('Earlier decisions')).toBeInTheDocument();
    expect(screen.getByText(/Previously released in error/)).toBeInTheDocument();
  });
});

describe('recording a decision', () => {
  /**
   * <strong>The mandatory reason.</strong> Blocked in the UI as well as the server, so the operator is told
   * before submitting rather than after.
   */
  it('will not submit without both an action and a reason', async () => {
    const user = await openReview();

    const submit = screen.getByRole('button', { name: /Release payment|Reject payment/ });
    expect(submit).toBeDisabled();

    await user.click(screen.getByRole('radio', { name: /Release/ }));
    expect(screen.getByRole('button', { name: 'Release payment' })).toBeDisabled();

    await user.type(screen.getByLabelText(/Reason/), 'Verified with the customer');
    expect(screen.getByRole('button', { name: 'Release payment' })).toBeEnabled();

    expect(fraudApi.decide).not.toHaveBeenCalled();
  });

  /** Whitespace is not a reason. Trimming here matches the server's own @NotBlank. */
  it('treats whitespace as no reason at all', async () => {
    const user = await openReview();

    await user.click(screen.getByRole('radio', { name: /Release/ }));
    await user.type(screen.getByLabelText(/Reason/), '     ');

    expect(screen.getByRole('button', { name: 'Release payment' })).toBeDisabled();
  });

  /** The server limit is 512. Warning before submission beats a 400 after typing a paragraph. */
  it('warns when the reason exceeds the server limit', async () => {
    const user = await openReview();

    await user.click(screen.getByRole('radio', { name: /Release/ }));

    // Pasted, not typed. `user.type` dispatches one key event per character, and 513 of them
    // takes long enough to exceed the test timeout — and a run that times out mid-typing leaks
    // its remaining keystrokes into whichever test follows.
    await user.click(screen.getByLabelText(/Reason/));
    await user.paste('x'.repeat(513));

    expect(screen.getByText(/too long, the server will refuse this/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Release payment' })).toBeDisabled();
  });

  /**
   * <strong>Claim, then decide.</strong> `take` first, so a collision surfaces at the start of the work
   * rather than at the end of it.
   */
  it('claims the alert before recording the decision', async () => {
    const user = await openReview();

    await user.click(screen.getByRole('radio', { name: /Release/ }));
    await user.type(screen.getByLabelText(/Reason/), 'Spoke to the customer.');
    await user.click(screen.getByRole('button', { name: 'Release payment' }));

    await waitFor(() => expect(fraudApi.decide).toHaveBeenCalled());

    expect(fraudApi.take).toHaveBeenCalledWith(ALERT.alertId);
    expect(fraudApi.decide).toHaveBeenCalledWith(ALERT.alertId, 'RELEASE', 'Spoke to the customer.');

    const takeOrder = vi.mocked(fraudApi.take).mock.invocationCallOrder[0] ?? 0;
    const decideOrder = vi.mocked(fraudApi.decide).mock.invocationCallOrder[0] ?? 0;
    expect(takeOrder).toBeLessThan(decideOrder);
  });

  it('sends REJECT when the payment is blocked', async () => {
    const user = await openReview();

    await user.click(screen.getByRole('radio', { name: /Reject/ }));
    await user.type(screen.getByLabelText(/Reason/), 'Confirmed account takeover.');
    await user.click(screen.getByRole('button', { name: 'Reject payment' }));

    await waitFor(() =>
      expect(fraudApi.decide).toHaveBeenCalledWith(
        ALERT.alertId,
        'REJECT',
        'Confirmed account takeover.',
      ),
    );
  });

  /**
   * <strong>The message must not overclaim.</strong> The decision is durable; the payment resumes
   * asynchronously through an outbox event the orchestrator consumes.
   */
  it('says the decision was recorded and the payment will resume, not that it completed', async () => {
    const user = await openReview();

    await user.click(screen.getByRole('radio', { name: /Release/ }));
    await user.type(screen.getByLabelText(/Reason/), 'Verified.');
    await user.click(screen.getByRole('button', { name: 'Release payment' }));

    const notice = await screen.findByRole('status');

    expect(notice).toHaveTextContent('Released.');
    expect(notice).toHaveTextContent('will resume shortly');
    expect(notice).toHaveTextContent('against your account');
    expect(notice.textContent).not.toMatch(/completed|settled|paid/i);
  });

  it('says the funds will be returned when a payment is rejected', async () => {
    const user = await openReview();

    await user.click(screen.getByRole('radio', { name: /Reject/ }));
    await user.type(screen.getByLabelText(/Reason/), 'Confirmed fraud.');
    await user.click(screen.getByRole('button', { name: 'Reject payment' }));

    expect(await screen.findByRole('status')).toHaveTextContent(
      /reserved funds will be returned shortly/,
    );
  });
});

describe('losing the race', () => {
  /**
   * A 409 on `take` means somebody else is already working it. Found out before the operator invests any
   * effort, which is the whole reason `take` runs first.
   */
  it('reports a claim taken by another analyst without submitting a decision', async () => {
    vi.mocked(fraudApi.take).mockRejectedValue(conflict('Already claimed'));

    const user = await openReview();
    await user.click(screen.getByRole('radio', { name: /Release/ }));
    await user.type(screen.getByLabelText(/Reason/), 'Verified.');
    await user.click(screen.getByRole('button', { name: 'Release payment' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /already claimed or decided this alert/,
    );
    expect(fraudApi.decide).not.toHaveBeenCalled();
  });

  /**
   * <strong>A 409 on `decide` must say nothing was changed.</strong> An ambiguous message here leaves the
   * operator unsure whether their decision or the other one is in force.
   */
  it('reports a decision made by somebody else and states that nothing changed', async () => {
    vi.mocked(fraudApi.decide).mockRejectedValue(conflict('Alert already decided'));

    const user = await openReview();
    await user.click(screen.getByRole('radio', { name: /Reject/ }));
    await user.type(screen.getByLabelText(/Reason/), 'Confirmed fraud.');
    await user.click(screen.getByRole('button', { name: 'Reject payment' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/decided by somebody else/);
    expect(alert).toHaveTextContent(/Nothing was changed/);
    // Still on the form, so the operator can refresh and see the real state.
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  /** The server's own field message, not a guess. It is the authoritative one. */
  it('surfaces the server field error on a 400', async () => {
    vi.mocked(fraudApi.decide).mockRejectedValue(
      new ApiError(400, {
        status: 400,
        code: 'VALIDATION_FAILED',
        message: 'Validation failed',
        fieldErrors: [{ field: 'reason', message: 'must not be blank' }],
      }),
    );

    const user = await openReview();
    await user.click(screen.getByRole('radio', { name: /Release/ }));
    await user.type(screen.getByLabelText(/Reason/), 'Verified.');
    await user.click(screen.getByRole('button', { name: 'Release payment' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('reason: must not be blank');
  });

  /** An alert that vanished is a different remedy from one that was decided, and it says so. */
  it('reports an alert that no longer exists', async () => {
    vi.mocked(fraudApi.take).mockRejectedValue(
      new ApiError(404, { status: 404, code: 'NOT_FOUND', message: 'no such alert' }),
    );

    const user = await openReview();
    await user.click(screen.getByRole('radio', { name: /Release/ }));
    await user.type(screen.getByLabelText(/Reason/), 'Verified.');
    await user.click(screen.getByRole('button', { name: 'Release payment' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/no longer exists/);
    expect(fraudApi.decide).not.toHaveBeenCalled();
  });

  /**
   * A network failure never reached the gateway, so nothing was recorded. Saying so is the difference between
   * "try again" and "check whether it went through".
   */
  it('states that nothing changed when the request never reached the platform', async () => {
    vi.mocked(fraudApi.decide).mockRejectedValue(new TypeError('Failed to fetch'));

    const user = await openReview();
    await user.click(screen.getByRole('radio', { name: /Release/ }));
    await user.type(screen.getByLabelText(/Reason/), 'Verified.');
    await user.click(screen.getByRole('button', { name: 'Release payment' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/Nothing was changed/);
  });
});
