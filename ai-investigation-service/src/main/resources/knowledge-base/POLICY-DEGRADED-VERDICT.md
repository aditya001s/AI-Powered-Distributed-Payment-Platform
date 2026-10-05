---
title: Verdicts reached without the fraud model
category: EVIDENCE_STANDARD
relatedRuleIds:
version: 1
source: AegisPay Fraud Operations Manual, section 3.7
---

# Verdicts reached without the fraud model

## What a degraded verdict means

When the scoring service cannot be reached, fraud-service still produces a verdict: the
deterministic rules run, and the result is marked `degradedMode`. The stored fraud
probability in that case is **0.0**, and that value means *unknown*, not *safe*.

This is the most easily misread field in the platform. A verdict of `LOW` risk with
`degradedMode: true` means "the model was unreachable and the rules found nothing". A
verdict of `LOW` with `degradedMode: false` means "the model examined this payment and
scored it as routine". Those are completely different levels of assurance, and an
investigation that treats a degraded 0.0 as a low score has reported the opposite of
what happened.

## What a degraded verdict is still worth

Not nothing. The deterministic rules cover the archetypes the model scores worst on —
bust-out at 0.147 measured recall, mule cash-out at 0.328 — so rules-only coverage is
strongest exactly where the model is weakest. Falling back to them is a reduced
assessment, not an absent one.

That is why the platform's default is to fall back rather than refuse every payment while
the model is down: refusing would turn a scoring-service outage into a total payment
outage, which is a far larger incident than the fraud it prevents.

## The stricter case: no assessment at all

There is a second, different degradation. If **fraud-service itself** is unreachable, the
saga fails the payment rather than letting it through, and the reasoning is the opposite
way round: a model outage leaves the rules running, but a fraud-service outage leaves no
control at all — no score, no rules, nothing. Recording an unassessed payment as approved
would be a false entry in the audit trail of an automated decision.

So a failed payment during a fraud-service outage is the system working as designed. An
investigation will not exist for it, because nothing assessed it.

## What an investigation must say

Where the verdict is degraded, the report must:

1. State it explicitly and early, because it changes how everything else reads.
2. Not cite the fraud probability as evidence. There is no probability; there is a
   placeholder.
3. Describe the rule findings as the whole of the machine evidence, not as a supplement
   to a score.
4. Lean towards recommending verification rather than either extreme. Neither "the model
   found nothing" nor "the model found something" is available.

An investigation that reports "the model assessed this payment at a 0% fraud probability"
on a degraded verdict is stating something that did not happen, and stating it in the most
reassuring possible form.

## What an analyst should check

1. **Confirm the degraded flag before reading anything else.** It is on the assessment and
   on the saga row.
2. **Read the rule hits as the primary evidence.** They are deterministic and they did run.
3. **Consider re-assessing once the model is back**, if the payment is still held. A fresh
   assessment is not automatic — assessments are idempotent per payment by design, because
   a verdict that changed on retry would be indefensible in a dispute — so this is a
   deliberate decision to record a new one.
