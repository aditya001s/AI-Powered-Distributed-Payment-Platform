---
title: Account takeover and new-device risk
category: ATTACK_PATTERN
relatedRuleIds: RULE_NEW_DEVICE_HIGH_VALUE,RULE_DORMANT_ACCOUNT_LARGE_PAYMENT
version: 1
source: AegisPay Fraud Operations Manual, section 4.1
---

# Account takeover and new-device risk

## What the pattern is

Somebody other than the owner is operating the account. The payment itself is often
unremarkable; what changed is the context around it — a device the account has never
used, a location it has never transacted from, a session that began with a credential
reset.

This is the archetype the model handles best: measured recall is 0.713 at the blocking
threshold, because the features that distinguish it are exactly the features a payment
carries. Device novelty, location novelty and departure from the account's usual amount
are all directly observable, so a statistical model has something to work with.

## Why a new device alone is not enough to act on

People replace phones. On any large platform, a meaningful share of active customers
transact from a new device each month, and a control that held every first payment from
a new handset would produce a self-inflicted outage on upgrade day and be switched off
by the end of it.

So the rule requires a new device **and** an amount that is a substantial multiple of
the account's usual — and even then the correct response is a review rather than a
hold, because the false-positive population is large and entirely innocent.

An important distinction in the data: **"no device supplied" is not "an unfamiliar
device"**. API-channel payments carry no device fingerprint at all. Treating absence as
novelty would flag every one of them, so the feature is null rather than true when no
device was reported. An investigation must not describe a missing fingerprint as a new
device.

## Dormant reactivation

A closely related pattern, and the reason this policy covers both rules. An account
nobody has used for ninety days that wakes up with a payment several times its usual
size is a quieter target than an active one: the real owner is not watching it, so a
compromise goes unnoticed for longer.

The response is a review rather than a hold. Dormancy has entirely ordinary
explanations — a seasonal customer, a secondary wallet, somebody who was abroad — and
the amount multiple is what makes it worth a look at all.

## What an analyst should check

1. **Did anything about the account change shortly before the payment?** A password
   reset, an email change, a new phone number. This is the strongest corroborating
   signal in the whole pattern, and it is the one an attacker cannot avoid producing.
2. **Is the destination new as well?** A new device paying a familiar counterparty is
   usually the customer on a new phone. A new device paying a new counterparty is a
   different picture.
3. **Does the device look like a real handset?** A fingerprint that changes on every
   request suggests automation rather than a replaced phone.
4. **Was there a failed authentication run first?** Successful takeover is often
   preceded by unsuccessful attempts.

## Recommended handling

Verify through a channel established **before** the suspicious session. This is the
single most important procedural point in this policy: confirming a payment through a
phone number changed an hour ago verifies the attacker, not the customer.

Where the only signal is device novelty and the amount is within the account's normal
range, this does not support holding the payment.
