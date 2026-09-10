package com.myharness.codex.service.mail;

/** Tests replace this boundary with a recording adapter; no business transaction performs SMTP IO. */
public interface MailDeliveryAdapter {
    void send(String recipient, String subject, String text);
}
