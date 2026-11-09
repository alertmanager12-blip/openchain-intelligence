package io.openchain.alerting.rule;

/** Notification channel kinds. A rule references a configured connector by id; it never carries a URL. */
public enum ActionType { WEBHOOK, SLACK, DISCORD, TELEGRAM, EMAIL, INTERNAL_EVENT }
