---
title: Credential and card testing bursts
category: ATTACK_PATTERN
relatedRuleIds: RULE_CARD_TESTING_VELOCITY
version: 1
source: AegisPay Fraud Operations Manual, section 4.3
---

# Credential and card testing bursts

## What the pattern is

Somebody in possession of a list of credentials is finding out which ones still work.
The signature is a burst of attempts in a short window with a high proportion refused.
Amounts are usually small, because the attacker is testing validity rather than
extracting value; the extraction comes later, from whichever credentials survived.

Two signals are required together:

- several attempts within an hour
- several failures within twenty-four hours

Either alone is ordinary. An expiring card produces a run of failures with no burst. A
customer doing their weekly shopping produces a burst with no failures. It is the
combination that has no innocent explanation.

## Why counting attempts rather than successes matters

The velocity features count **attempts**, including refused ones. This is deliberate
and it is the whole reason the pattern is detectable: a control that only counted
successful payments would see almost nothing during a testing run, because the
attacker's own failures are the evidence. A burst of declines is one of the strongest
signals in fraud detection and it is invisible to any system that measures throughput.

The account's own behavioural baseline is computed from **completed** payments only,
for a related reason. If failed attempts raised the baseline, an attacker could
inflate an account's apparent normal volume with a testing run and make the payment
that follows look proportionate. The attacker would be setting their own threshold.

## What an analyst should check

1. **Are the failures spread across destinations or concentrated on one?** Many
   destinations suggests credential testing. One destination retried repeatedly is
   more often a customer with a genuine problem.
2. **Do the amounts cluster at the low end?** Testing uses small amounts. A burst of
   large attempts is a different pattern and usually a different response.
3. **Is one device or IP behind all of them?** Shared origin across many accounts is
   the strongest confirmation available, and it changes the response from
   payment-level to account-level.
4. **Did any attempt succeed?** A successful payment inside a testing burst is the one
   that needs attention first, because it is the credential the attacker now knows is
   live.

## Recommended handling

Hold and rate-limit rather than refuse outright. The account may well belong to a
customer whose credentials were stolen elsewhere, and a terminal refusal punishes them
for somebody else's breach.

Where a burst is confirmed across multiple accounts from a shared origin, the
proportionate response is an infrastructure-level control, not a decision on any single
payment. Recommend escalation: this is not a case an individual payment review can
resolve, and reviewing each payment separately will produce the same answer many times
while the burst continues.
