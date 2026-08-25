/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail;

import jakarta.activation.DataHandler;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.util.Properties;

/** Sends mail over SMTP via Jakarta Mail. multipart/mixed[ related[ html + inline ] + attachments ]. */
public class SmtpMailSender implements MailSender {

    private final MailConfig config;

    public SmtpMailSender(MailConfig config) {
        this.config = config;
    }

    @Override
    public boolean isConfigured() {
        return config.isConfigured();
    }

    @Override
    public void send(MailMessage m) {
        try {
            Session session = session();
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(m.from()));
            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(m.to()));
            msg.setSubject(m.subject(), "UTF-8");

            // saiku#1811 PR2: optional List-Unsubscribe plumbing. ADDITIVE + null-safe — when the
            // message carries no unsubscribe value the headers are never touched, so the existing
            // self-send / test-send MIME output is byte-for-byte unchanged. When present we emit the
            // RFC 2369 List-Unsubscribe header plus the RFC 8058 one-click marker.
            // saiku#1811 PR4 (SEC carry-forward #1): now that a real per-recipient value is set, CRLF-strip
            // it before setHeader so a smuggled CR/LF can't inject an extra SMTP header (header injection).
            String listUnsub = stripCrlf(m.listUnsubscribe());
            if (listUnsub != null && !listUnsub.isBlank()) {
                msg.setHeader("List-Unsubscribe", listUnsub);
                msg.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
            }

            MimeMultipart related = new MimeMultipart("related");
            MimeBodyPart html = new MimeBodyPart();
            html.setContent(m.htmlBody(), "text/html; charset=UTF-8");
            related.addBodyPart(html);
            for (InlineImage img : m.inlineImages()) {
                MimeBodyPart part = new MimeBodyPart();
                part.setDataHandler(new DataHandler(new ByteArrayDataSource(img.data(), img.contentType())));
                part.setContentID("<" + img.contentId() + ">");
                part.setDisposition(MimeBodyPart.INLINE);
                related.addBodyPart(part);
            }
            MimeBodyPart relatedWrapper = new MimeBodyPart();
            relatedWrapper.setContent(related);

            MimeMultipart mixed = new MimeMultipart("mixed");
            mixed.addBodyPart(relatedWrapper);
            for (Attachment att : m.attachments()) {
                MimeBodyPart part = new MimeBodyPart();
                part.setDataHandler(new DataHandler(new ByteArrayDataSource(att.data(), att.contentType())));
                part.setFileName(att.filename());
                part.setDisposition(MimeBodyPart.ATTACHMENT);
                mixed.addBodyPart(part);
            }
            msg.setContent(mixed);
            Transport.send(msg);
        } catch (MessagingException e) {
            throw new MailException("SMTP send to " + m.to() + " failed: " + e.getMessage(), e);
        }
    }

    private Session session() {
        Properties p = new Properties();
        p.put("mail.smtp.host", config.host());
        p.put("mail.smtp.port", String.valueOf(config.port()));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.writetimeout", "10000");
        boolean auth = config.username() != null && !config.username().isBlank();
        p.put("mail.smtp.auth", String.valueOf(auth));
        if (config.startTls()) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
        }
        if (config.ssl()) p.put("mail.smtp.ssl.enable", "true");
        if (auth) {
            return Session.getInstance(p, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(config.username(), config.password());
                }
            });
        }
        return Session.getInstance(p);
    }

    /**
     * Strip CR/LF (and trim) so a header value can never smuggle an extra SMTP header via injection
     * (saiku#1811 PR4, SEC carry-forward #1). Returns null when the input is null; blank after stripping
     * is returned as-is (the caller treats blank as "no header").
     */
    private static String stripCrlf(String s) {
        if (s == null) {
            return null;
        }
        return s.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
