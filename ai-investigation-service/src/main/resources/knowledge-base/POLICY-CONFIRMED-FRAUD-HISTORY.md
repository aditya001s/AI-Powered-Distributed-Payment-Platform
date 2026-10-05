---
title: Accounts with previously confirmed fraud
category: DECISION_AUTHORITY
relatedRuleIds: RULE_CONFIRMED_FRAUD_HISTORY
version: 1
source: AegisPay Fraud Operations Manual, section 3.6
---

# Accounts with previously confirmed fraud

## Why this is the only automatic refusal

Every other control in this platform can, at most, hold a payment and wait for a person.
This one can refuse it outright, and the reason is that it rests on prior human
judgement rather than on a threshold somebody tuned.

An analyst has already examined payments on this account and recorded that they were
fraudulent. That is a fact, not a statistical claim, and it is the bar an automatic
refusal has to meet. A model score of 0.99 does not meet it: however high, a probability
is an inference about a population applied to an individual, and refusing a customer's
payment on an inference is a decision that should have a person's name attached.

Thresholds: one prior confirmed fraud puts the payment under review; three refuse it.

## What "confirmed fraud" means here, precisely

This platform has no card network, so it has **no chargebacks**. The industry-standard
feature `prior_chargeback_count` therefore cannot be populated, and inventing a column
called "chargebacks" would be describing something structurally impossible here.

The honest equivalent is an analyst's REJECT decision on a fraud alert: a human
confirming that a specific payment was fraudulent. That is what the count is derived
from, and it closes a real loop — a refusal recorded today raises the score on this
account's next payment.

An investigation must describe this accurately. "This account has three chargebacks" is
false. "An analyst has previously confirmed three payments on this account as
fraudulent" is what the data says.

## What an analyst should check

Almost nothing about the payment, and quite a lot about the history:

1. **Read the prior decisions.** They carry a mandatory reason. If all three refusals
   came from one incident on one day, that is a different picture from three separate
   incidents over a year.
2. **Who recorded them?** A cluster from a single reviewer during a single shift is worth
   a second look, particularly if the reasons are terse or identical.
3. **Was the account remediated?** If credentials were reset and the customer verified
   after the last confirmed fraud, the history may describe a compromise that has since
   been resolved rather than a customer who defrauds.
4. **Is this the same pattern?** A repeat of the previously confirmed pattern is far
   stronger than an unrelated payment on a tainted account.

## Why a refusal here is still not an account closure

Refusing a payment and closing an account are different decisions with different
consequences and different authority. This control refuses payments. Nothing in the
automated path closes, freezes or restricts an account, and no recommendation this
service produces should be read as advising one without a named human decision behind
it.

## Recommended handling

Where the history is genuine and unremediated, the automatic refusal stands and the
appropriate recommendation is to review the account — a decision for a senior analyst,
not for this control and not for an AI.

Where the history looks like one resolved incident counted three times, say so
explicitly. A miscounted history that permanently refuses a legitimate customer's
payments is a serious harm, and it is invisible unless somebody reads the prior
decisions.
