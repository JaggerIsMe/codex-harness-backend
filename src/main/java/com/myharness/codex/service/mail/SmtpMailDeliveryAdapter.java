package com.myharness.codex.service.mail;

import com.myharness.codex.config.AccountMailProperties;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

@Component
public class SmtpMailDeliveryAdapter implements MailDeliveryAdapter {
    private final ObjectProvider<JavaMailSender> sender;
    private final AccountMailProperties properties;
    public SmtpMailDeliveryAdapter(ObjectProvider<JavaMailSender> sender, AccountMailProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    @Override
    public void send(String recipient, String subject, String text) {
        JavaMailSender configuredSender = sender.getIfAvailable();
        if (configuredSender == null || !AccountMailSettings.isMailbox(recipient)) throw new MailSendException("MAIL_CONFIGURATION_INVALID");
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(new InternetAddress(properties.getFrom(), properties.getFromName(), StandardCharsets.UTF_8.name()).toString());
            message.setTo(recipient);
            message.setSubject(subject);
            message.setText(text);
            configuredSender.send(message);
        } catch (UnsupportedEncodingException ex) { throw new MailSendException("MAIL_ENCODING_INVALID"); }
    }
}
