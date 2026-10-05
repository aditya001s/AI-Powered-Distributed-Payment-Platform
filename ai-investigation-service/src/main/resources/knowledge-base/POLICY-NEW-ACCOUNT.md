---
title: First payments and accounts with no history
category: EVIDENCE_STANDARD
relatedRuleIds: RULE_FIRST_PAYMENT_UNUSUALLY_LARGE
version: 1
source: AegisPay Fraud Operations Manual, section 3.4
---

# First payments and accounts with no history

## Why the absence of history is not evidence

Every behavioural control in this platform compares a payment to the account's own
past. On a first payment there is no past, so every one of those controls has nothing to
say — and "nothing to say" must not be recorded as "nothing wrong" or as "suspicious".
It is genuinely no evidence either way.

This is why the first-payment rule is **advisory only**. It records that a new account
has made a large payment and it does not escalate, because escalating would penalise
every new customer at the most expensive possible moment: the first thing they ever try
to do. A platform that holds first payments does not acquire customers.

## How absent features are represented, and why it matters

Unknown values are `null` throughout the pipeline, never zero. The distinction is not
pedantic:

- `avgAmountMinor: null` means this account has no completed payments to average.
- `avgAmountMinor: 0` would mean its usual payment is nothing, which is false.

A zero would also make every ratio computed against it meaningless or infinite, and any
rule dividing by it would either fire on everything or crash. The model is trained to
treat absence as its own signal, which it can only do if absence is actually
representable.

An investigation reading a feature snapshot must not describe a `null` as a zero. "The
account's average payment is 0.00" is a false statement about a real customer; "this
account has no completed payments to compare against" is what the snapshot says.

## Related: the window aggregates exclude the payment being scored

Every velocity figure — payments in the last hour, amount in the last twenty-four hours
— covers the period **before** this payment and excludes it. Two consequences that look
like bugs and are not:

- `paymentCount1h: 0` on a payment means it is the first in that hour, not that no
  payment happened.
- `amountSum24hMinor` can be **smaller** than the amount being investigated, and is
  zero on a customer's first payment of the day.

An investigation that reports "this payment of 20,000.00 brings the 24-hour total to
0.00" has misread the contract. The supportable statement is that the account had
transacted nothing in the preceding twenty-four hours.

## What an analyst should check

1. **Is there any non-behavioural signal?** On a first payment, device, location and
   destination are all there is. A first payment from a data-centre IP to a brand-new
   destination is a different case from one from a residential connection to a known
   merchant.
2. **Is the amount plausible as a first transaction?** Some legitimate first payments are
   large — somebody funding a wallet to make a specific purchase. The question is whether
   the destination fits that story.
3. **Resist the urge to find something.** A first payment with no adverse signal is a new
   customer, and that is the most common explanation by a wide margin.

## Recommended handling

Where the only finding is that this is a large first payment, the correct recommendation
is no action or monitoring. Say plainly that the account has no history and that this
limits what can be concluded — an investigation whose honest answer is "there is not
enough here" is more useful than one that manufactures a concern.
