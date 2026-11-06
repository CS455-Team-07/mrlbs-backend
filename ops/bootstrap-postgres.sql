\set ON_ERROR_STOP on
BEGIN;
\ir ../src/main/resources/db/migration/V1__create_resources.sql
\ir ../src/main/resources/db/migration/V2__add_resource_pricing_limits.sql
\ir ../src/main/resources/db/migration/V3__create_bookings_and_credit_accounts.sql
\ir ../src/main/resources/db/migration/V4__add_resource_management_and_booking_rules.sql
\ir ../src/main/resources/db/migration/V5__create_members.sql
\ir ../src/main/resources/db/migration/V6__update_bookings_schema.sql
\ir ../src/main/resources/db/migration/V7__enforce_booking_status_values.sql
\ir ../src/main/resources/db/migration/V8__create_resource_telemetry_mappings.sql
\ir ../src/main/resources/db/migration/V8__create_waitlist_tables.sql
\ir ../src/main/resources/db/migration/V9__add_waitlist_pricing_columns.sql
\ir ../src/main/resources/db/migration/V9__create_booking_ai_reviews_and_outbox.sql
\ir ../src/main/resources/db/migration/V10__create_notifications_table.sql
\ir ../src/main/resources/db/migration/V11__update_waitlist_status_check.sql
\ir ../src/main/resources/db/migration/V12__add_rejection_reason_to_bookings.sql
\ir ../src/main/resources/db/migration/V13__align_ai_review_confidence_type.sql
COMMIT;
