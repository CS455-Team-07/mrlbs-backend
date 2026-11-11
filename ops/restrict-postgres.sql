\set ON_ERROR_STOP on
\if :{?runtime_role}
\else
\echo Pass -v runtime_role=ROLE for the existing unprivileged application login.
SELECT 1 / 0;
\endif

SELECT current_database() AS database_name \gset
BEGIN;
REVOKE ALL ON DATABASE :"database_name" FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;

GRANT CONNECT ON DATABASE :"database_name" TO :"runtime_role";
GRANT USAGE ON SCHEMA public TO :"runtime_role";
GRANT SELECT, INSERT, UPDATE, DELETE ON
    public.members, public.credit_accounts, public.resources, public.resource_rules,
    public.booking_rules, public.bookings, public.waitlist_entries, public.waitlist_offers,
    public.in_app_notifications, public.booking_ai_reviews, public.booking_outbox_events,
    public.resource_telemetry_mappings
    TO :"runtime_role";
GRANT USAGE ON SEQUENCE public.booking_rules_id_seq TO :"runtime_role";
COMMIT;
