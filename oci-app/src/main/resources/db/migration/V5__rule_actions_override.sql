-- Operator's notification routing for a repository rule (JSON array of rule actions). Set when an operator changes the
-- actions of a rule loaded from the rule pack; re-applied to every newer version of the pack file, so a rule pack
-- update keeps the operator's connectors (e.g. Telegram) instead of the pack's defaults. NULL = the pack's own actions.
alter table rules add column actions_override text;
