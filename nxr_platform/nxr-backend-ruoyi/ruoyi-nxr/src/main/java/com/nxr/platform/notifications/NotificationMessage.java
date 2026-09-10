package com.nxr.platform.notifications;

/** Fully rendered message passed to a transport. It is never written to logs. */
public record NotificationMessage(String recipient, String subject, String textBody) {
}
