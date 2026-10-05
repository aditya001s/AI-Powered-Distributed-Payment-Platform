#!/bin/sh
set -eu

# Create one login/owner per service database. Credentials arrive only through the
# postgres container environment; they are never embedded in this repository.
create_service_database() {
  database="$1"
  role="$2"
  credential="$3"

  psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set=database="$database" --set=role="$role" --set=credential="$credential" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'role', :'credential')
  WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role') \gexec
SELECT format('ALTER ROLE %I PASSWORD %L', :'role', :'credential') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'database', :'role')
  WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'database') \gexec
SQL
}

create_service_database "${AUTH_DB_NAME:-aegis_auth}" aegis_auth_user "$AUTH_DB_PASSWORD"
create_service_database "${WALLET_DB_NAME:-aegis_wallet}" aegis_wallet_user "$WALLET_DB_PASSWORD"
create_service_database "${LEDGER_DB_NAME:-aegis_ledger}" aegis_ledger_user "$LEDGER_DB_PASSWORD"
create_service_database "${PAYMENT_DB_NAME:-aegis_payment}" aegis_payment_user "$PAYMENT_DB_PASSWORD"
create_service_database "${ORCHESTRATOR_DB_NAME:-aegis_orchestrator}" aegis_orchestrator_user "$ORCHESTRATOR_DB_PASSWORD"
create_service_database "${FRAUD_DB_NAME:-aegis_fraud}" aegis_fraud_user "$FRAUD_DB_PASSWORD"
create_service_database "${AI_DB_NAME:-aegis_ai}" aegis_ai_user "$AI_DB_PASSWORD"
create_service_database "${NOTIFICATION_DB_NAME:-aegis_notification}" aegis_notification_user "$NOTIFICATION_DB_PASSWORD"
create_service_database "${ANALYTICS_DB_NAME:-aegis_analytics}" aegis_analytics_user "$ANALYTICS_DB_PASSWORD"

configure_service_database() {
  database="$1"
  role="$2"
  psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$database" \
    --set=role="$role" <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('GRANT ALL ON SCHEMA public TO %I', :'role') \gexec
SQL
}

configure_service_database "${AUTH_DB_NAME:-aegis_auth}" aegis_auth_user
configure_service_database "${WALLET_DB_NAME:-aegis_wallet}" aegis_wallet_user
configure_service_database "${LEDGER_DB_NAME:-aegis_ledger}" aegis_ledger_user
configure_service_database "${PAYMENT_DB_NAME:-aegis_payment}" aegis_payment_user
configure_service_database "${ORCHESTRATOR_DB_NAME:-aegis_orchestrator}" aegis_orchestrator_user
configure_service_database "${FRAUD_DB_NAME:-aegis_fraud}" aegis_fraud_user
configure_service_database "${AI_DB_NAME:-aegis_ai}" aegis_ai_user
configure_service_database "${NOTIFICATION_DB_NAME:-aegis_notification}" aegis_notification_user
configure_service_database "${ANALYTICS_DB_NAME:-aegis_analytics}" aegis_analytics_user

psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "${AI_DB_NAME:-aegis_ai}" \
  -c 'CREATE EXTENSION IF NOT EXISTS vector;'
psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "${AUTH_DB_NAME:-aegis_auth}" \
  -c 'CREATE EXTENSION IF NOT EXISTS pg_trgm;'
