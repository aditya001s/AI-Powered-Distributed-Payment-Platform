---
title: Impossible travel between consecutive payments
category: ATTACK_PATTERN
relatedRuleIds: RULE_IMPOSSIBLE_TRAVEL
version: 1
source: AegisPay Fraud Operations Manual, section 4.5
---

# Impossible travel between consecutive payments

## What the finding actually says

Two payments from the same account imply a ground speed that no journey can produce.
This is not a risk score. It is a contradiction: either the location data is wrong, or
one of the two payments was not made by the person who made the other.

The threshold is set around 900 km/h, roughly commercial cruise speed. It is
deliberately not lower. A genuine long-haul flight followed by a payment through an
airport network produces surprisingly high implied speeds, and a threshold set at, say,
200 km/h would flag every business traveller on the platform.

## Why a model cannot express this

A model can learn that large distances correlate with fraud. It cannot learn "this is
physically impossible", because the impossibility is not in either feature — it is in
the ratio of distance to elapsed time. Neither the distance nor the interval is
remarkable alone; only the quotient is, and a gradient booster is not given the
quotient.

This is the clearest case in the platform where a deterministic rule expresses
something a statistical model structurally cannot.

## Why the finding is often about data, not fraud

Most impossible-travel hits are not fraud. In order of likelihood:

1. **A VPN or corporate proxy.** The IP resolves to wherever the exit node is. This is
   the single most common cause, and it is entirely legitimate behaviour.
2. **Mobile network geolocation error.** Carrier-grade NAT can place a customer in a
   different city, occasionally a different country.
3. **A stale coordinate on one of the two payments.** A cached location from an earlier
   session.
4. **Two people legitimately using one account.** A shared household or business
   account.
5. **Account takeover.** Real, and the reason the rule exists, but not the base case.

An investigation that presents this finding as evidence of fraud without addressing the
alternatives is overstating what the data supports.

## What an analyst should check

1. **Does the country pair suggest a proxy?** A payment from a data-centre IP range is
   a strong indicator of route 1.
2. **Did the device stay the same?** Same device, distant locations, points to a network
   artefact. Different device *and* distant location is a far stronger signal.
3. **What was the interval?** Eleven minutes across continents is a contradiction.
   Nine hours across the same distance is a flight.
4. **Is either location the account's usual one?** A departure from a familiar location
   to an unfamiliar one matters more than two unfamiliar locations, which more often
   means the location data is simply unreliable for this customer.

## Recommended handling

Treat this as a prompt to verify, not as a finding of fraud. Where the device is
unchanged and the country pair is consistent with a proxy, this finding on its own does
not support holding a payment, and a report should say so.

Where it coincides with a new device or a new destination, the combination is
materially stronger than either part and a hold with customer verification is
proportionate.
