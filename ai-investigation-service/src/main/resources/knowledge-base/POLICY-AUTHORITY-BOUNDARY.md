---
title: The authority boundary between AI and human decisions
category: DECISION_AUTHORITY
relatedRuleIds:
version: 1
source: AegisPay Fraud Operations Manual, section 1.1
---

# The authority boundary between AI and human decisions

## The rule

An investigation recommends. A person decides. There is no path by which a generated
recommendation moves money, refuses a payment, or restricts an account.

This is not a policy anyone has to remember to follow. It is enforced by three things:

1. **Vocabulary.** Recommendations come from a closed enumerated set, and every value in it
   is phrased as advice to a person. There is no `BLOCK_ACCOUNT` and no `RELEASE_PAYMENT`,
   because a name like that invites somebody to wire it to an action.
2. **Absence of capability.** The investigation service holds no wallet client, no ledger
   client, and its service-to-service authentication is switched off entirely. It has no
   credential any internal write API would accept. Naming discipline is a convention;
   having no credentials is a guarantee.
3. **Consumption.** Nothing on the platform subscribes to a completed investigation and
   acts on it. Releasing a held payment requires an analyst decision recorded in
   fraud-service, attributed to their token.

## Why this boundary rather than a stricter or looser one

**Looser** — letting a high-confidence recommendation act automatically — fails on the
asymmetry of the errors. A wrongly released payment costs the amount, usually
unrecoverably. A wrongly refused one costs a customer their payment and the platform their
trust. Neither is a decision to make on a language model's confidence estimate, which is
not calibrated and cannot be, because there is no ground truth to calibrate it against.

**Stricter** — not producing a recommendation at all — was considered and rejected. An
investigation that assembles evidence and then declines to draw a conclusion pushes the
hardest part back onto the analyst while still costing them a read. The recommendation is
the useful part; the constraint is that it is advice.

## What the recommendation vocabulary means

In increasing severity:

- `NO_ACTION_REQUIRED` — the evidence does not support concern.
- `MONITOR_ACCOUNT` — nothing actionable now, worth watching.
- `REQUEST_CUSTOMER_VERIFICATION` — contact the customer, without stopping the payment.
- `INSUFFICIENT_EVIDENCE` — not enough here to say anything useful. A real answer.
- `HOLD_AND_REQUEST_VERIFICATION` — keep the payment held and confirm before releasing.
- `ESCALATE_TO_SENIOR_ANALYST` — needs more experience or a wider view than a payment
  review provides. Also the landing place for anything that goes wrong during generation.
- `RECOMMEND_REJECT_AND_REVIEW_ACCOUNT` — the most severe, and still advice. Refusing
  happens when an analyst records a REJECT in fraud-service.

## Why agreement is recorded

When an analyst reviews an investigation, their own assessment is recorded in the same
vocabulary alongside whether they agreed. That makes one question answerable: **was the
recommendation followed?**

It is the only honest measure of whether this service earns its keep. A recommendation
nobody follows is worse than none, because it costs a read and teaches analysts to skip
the next one. Recording the analyst's own assessment rather than just "disagreed" also
shows the *direction* of the disagreement, and a model that is consistently one step too
cautious needs the opposite fix to one that is consistently too relaxed.

Agreement is deliberately computed from the two assessments rather than accepted from the
client, so it cannot disagree with the values it summarises.
