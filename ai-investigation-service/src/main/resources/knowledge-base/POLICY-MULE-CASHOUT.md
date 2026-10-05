---
title: Money-mule cash-out corridors
category: ATTACK_PATTERN
relatedRuleIds: RULE_UNFAMILIAR_CASH_OUT_CORRIDOR
version: 1
source: AegisPay Fraud Operations Manual, section 4.4
---

# Money-mule cash-out corridors

## What the pattern is

Funds obtained elsewhere are being moved out of the platform through an account that
exists for that purpose. The signature is a withdrawal to a destination the account has
never used, initiated from a country the account has never transacted from.

All three elements are required. A withdrawal to a new destination is how anybody pays
somebody for the first time. A payment from an unfamiliar country is how anybody
travels. It is the conjunction — cash-out, new counterparty, new geography — that
narrows it to something worth a human's time, and even then the response is a review
rather than a hold, because an ordinary holiday withdrawal produces two of the three.

## Why the model is weak here

Measured recall for this archetype is 0.328 at the blocking threshold — better than
bust-out, far from covered. The difficulty is that a mule account often has no
anomalous history at all: it was opened for this, so there is no baseline to depart
from, and "no history" reads to a model much the same as "a new customer".

The rule adds the geographic and counterparty conjunction, which is information about
the *shape* of the cash-out rather than about the account's past. That is what makes it
complementary rather than redundant.

## What an analyst should check

1. **How old is the account, and what did it do first?** A mule account's first activity
   is usually an inbound receipt followed quickly by a withdrawal. A long history of
   ordinary two-way activity argues against.
2. **Where did the funds come from?** An inbound payment from an account that is itself
   under review is the strongest signal available, and it is a question about the
   counterparty rather than about this payment.
3. **Is the outbound amount close to the inbound one?** Pass-through with a small margin
   retained is characteristic. A withdrawal from an accumulated balance is not.
4. **Is the corridor one the platform sees repeatedly?** A single unfamiliar corridor is
   weak evidence. The same corridor across several unrelated accounts is not.

## Why country must be handled carefully

Country of origin is used as a feature, and it is the one signal in this platform with
a real discrimination exposure. Two constraints follow:

- The country-risk handling lives in reviewed source, never in configuration. A risk
  list that could be edited by an environment variable is a list nobody reviewed.
- A finding must never be phrased as though a country were itself suspicious. The
  finding is that the country is **new for this account** — a departure from its own
  established pattern — which is a statement about the account, not about the place.

An investigation that says "the payment originated from a high-risk country" has
overstated the evidence and stated it in a form that would be indefensible if quoted
back. The supportable form is "the payment originated from a country this account has
not transacted from before".

## Recommended handling

Review rather than hold, unless another finding coincides. Where the pass-through
pattern is present — inbound receipt followed promptly by a matching withdrawal —
escalate: this needs an account-level view that a single payment review cannot provide.
