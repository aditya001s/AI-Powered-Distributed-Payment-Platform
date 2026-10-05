---
title: Handling a held payment, and the cost of delay
category: PROCEDURE
relatedRuleIds:
version: 1
source: AegisPay Fraud Operations Manual, section 2.1
---

# Handling a held payment, and the cost of delay

## What a hold actually is

A held payment has stopped before any provider was charged, and the customer's funds are
**still reserved**. Nothing has moved. The customer cannot spend that money, and from
their point of view the payment is simply not happening and nobody has told them why.

That is the cost to weigh. A hold is reversible and a refusal is not, which is why every
automated control in this platform holds rather than refuses — but "reversible" is not
"free". Every open hold is somebody unable to use their own money.

## The reservation does not last forever

A payment reservation is created with a fifteen-minute lifetime, sized for a payment that
completes in seconds. A held payment waits on a person instead, so the reservation is
**extended** when the payment is parked — currently to two days, capped at seven.

Two consequences an analyst should understand:

1. **A review that outruns the extension causes the capture to fail.** The funds are
   released, the customer can spend them, and releasing the payment afterwards will fail
   rather than take money the account no longer has. The payment fails safely, but it
   fails, and the customer has to start again.
2. **The cap is deliberate.** Extension must not become a way to freeze money
   indefinitely, which is the exact failure the expiry exists to prevent. A forgotten
   alert eventually frees the customer's funds rather than sitting on them.

The practical implication: a hold has a working life measured in days, not weeks. An
investigation that cannot be resolved in that window should be escalated, not left open.

## Verification, done properly

Where a hold requires customer contact, the channel matters more than the question:

- Use contact details that were on file **before** the payment was attempted.
- Do not accept confirmation through the session that initiated the payment. If the
  account is compromised, that session is the attacker.
- Do not use details changed in the last thirty days.

A confirmation obtained through a compromised channel is worse than no confirmation,
because it converts a suspicion into a documented approval.

## Release, refuse, or escalate

**Release** when the customer has confirmed on a known channel, or when the findings turn
out to have an ordinary explanation. Record why: the reason is the only account of why a
held payment was let through, and it is what a later dispute reads.

**Refuse** only where the evidence needs no further interpretation. In practice this means
confirmed prior fraud, or a customer stating that they did not make the payment. A refusal
on a balance of probabilities is a decision that should be escalated, not made.

**Escalate** when the customer cannot be reached, when the pattern spans several accounts,
or when the answer depends on information a payment review does not have. Escalating a
payment nobody needed to escalate costs an analyst a few minutes. Guessing costs a
customer their money or the platform a fraud.

## What the AI investigation is for, and what it is not

An investigation assembles the evidence, cites the policy that applies, and recommends.
It has no capability to release or refuse anything, holds no credentials for any wallet or
ledger API, and its recommendation is advice to a person.

Read it as a starting point that has already done the reading. Do not treat a
recommendation as a decision: the decision is recorded against your name, not the model's.
