# Kafka dead-letter operations

AegisPay routes records that exhaust consumer retries to `<source-topic>.DLT`. A DLT is a durable quarantine stream, not an automatic retry queue. Automatic replay is intentionally absent: replaying malformed data or an event whose side effect partially committed can repeat damage, and a generic endpoint cannot preserve the source event's ordering decision safely.

## Detection

Prometheus alert `DurableEventDeliveryFailed` fires when `aegis_events_dead_lettered_total` increases. Logs include source topic, partition, offset, event ID, and failure. Notification transport dead letters are separate database work items and use the authenticated `/api/admin/notifications/**` replay workflow.

List DLTs in the local Compose broker:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh `
  --bootstrap-server localhost:9092 --list | Select-String '\.DLT$'
```

Inspect a quarantined topic without changing offsets:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh `
  --bootstrap-server localhost:9092 `
  --topic payment-created.DLT `
  --from-beginning --max-messages 20 `
  --property print.key=true --property print.headers=true `
  --property print.partition=true --property print.offset=true
```

## Required triage before replay

1. Capture source topic/partition/offset, key, event ID, correlation ID, payload, exception, and deployment version.
2. Classify the failure:
   - **Permanent contract/data error:** fix or quarantine; never replay unchanged.
   - **Transient dependency failure:** confirm dependency recovery and consumer readiness.
   - **Possible partial side effect:** reconcile the owning service by its natural idempotency key before replay.
3. Check owning-service state and idempotency:
   - payment: `paymentId`
   - saga/provider: `sagaId` / provider merchant reference
   - wallet hold/capture: `paymentId`
   - ledger: deterministic `ledgerTransactionId`
   - notification: `deduplicationKey`
   - investigation: automatic unique payment ID
4. Obtain two-person approval for money-moving topics.
5. Replay to the **original source topic**, preserving key, headers, and payload. Do not pipe `kafka-console-consumer` directly into a producer: that loses structured headers and can change partition/order.
6. Verify resulting database state, outbox status, ledger reconciliation, and consumer lag; retain the incident record.

## Production tooling requirement

Use an organization-approved Kafka operations tool that preserves keys and headers and writes an immutable replay audit record. Grant it topic-specific ACLs only during the change window. This repository does not embed cluster-admin Kafka credentials or expose a blanket HTTP replay endpoint, because either would create a higher-severity control than the operational gap it tries to solve.

DLT retention is configured longer than source retention by `KafkaConfig`; alert routing, incident ownership, and replay approval remain deployment-organization responsibilities.
