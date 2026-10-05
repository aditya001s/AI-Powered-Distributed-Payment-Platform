---
title: Bust-out pattern on established accounts
category: ATTACK_PATTERN
relatedRuleIds: RULE_BUST_OUT_PATTERN
version: 2
source: AegisPay Fraud Operations Manual, section 4.2
---

# Bust-out pattern on established accounts

## What the pattern is

A bust-out is an account that behaves normally for a long period and then attempts to
move the largest amount it ever has, usually to a counterparty it has never paid
before. The patient history is the point: it is what makes the account look
trustworthy to any control that scores behaviour against a baseline, because the
account built that baseline deliberately.

The signature is a conjunction, not a single signal:

- an established payment history, typically twenty or more completed payments
- an amount that is a substantial multiple of the account's largest previous payment
- a destination the account has never sent funds to

## Why the model does not catch this on its own

Measured recall for this archetype on held-out data is 0.147 at the blocking
threshold. That is not a tuning failure. Feature for feature, a bust-out is nearly
identical to a real customer making their first genuinely large purchase: both have a
long clean history, both exceed their previous maximum, and both are paying somebody
new. The features that would separate them are not present in a payment.

The deterministic rule carries this case almost alone, and it is written as a
conjunction precisely because no single element is actionable. Exceeding a historic
maximum is completely ordinary — customers do it whenever they buy something
expensive — and a rule that fired on it would hold a large share of legitimate
traffic and would have to be switched off.

## What an analyst should check

Order these by how quickly they settle the question:

1. **Is the destination plausible for this customer?** A new counterparty that is a
   merchant in a category the customer already uses is far weaker evidence than a new
   personal wallet with no history on the platform.
2. **Does the amount have a story?** A round number at a threshold boundary is more
   suspicious than an odd amount that looks like a real invoice.
3. **Did anything else about the session change?** A bust-out executed by somebody who
   has taken over the account will usually also show a new device or a new location. A
   bust-out by the account's real owner — a first-party fraud — will not, and that is
   the harder case.
4. **Has the account recently changed contact details?** A password or phone change
   shortly before a large payment is the strongest corroborating signal available.

## Recommended handling

Hold the payment and contact the customer through a channel that was already on file
before the payment was attempted. Do not use contact details changed in the last
thirty days, and do not accept confirmation through the same session that initiated
the payment: if the account is compromised, the attacker controls both.

If the customer confirms the payment on a known channel, release it. A confirmed
bust-out false positive costs the customer a delay of minutes; releasing an unverified
one costs them the whole amount, and the funds are usually unrecoverable because the
destination is chosen to be.

Where the customer cannot be reached, escalate rather than release. An unreachable
customer is not evidence that the payment is fine.
