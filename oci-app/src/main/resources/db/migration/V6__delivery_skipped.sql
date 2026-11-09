-- SKIPPED deliveries: no connector could receive the notification when the alert was raised (e.g. the rule pack's
-- default-webhook placeholder without OCI_DEFAULT_CONNECTOR). Recorded instead of a doomed attempt and a dead letter.
alter table deliveries drop constraint deliveries_status_check;
alter table deliveries add constraint deliveries_status_check check (status in ('PENDING', 'DELIVERED', 'DEAD_LETTER', 'SKIPPED'));
