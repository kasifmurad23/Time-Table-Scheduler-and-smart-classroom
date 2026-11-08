package com.tssc.backend;

import com.tssc.backend.dto.ApiDtos.SendEmailRequest;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private final JavaMailSender mailSender;
    private final String smtpHost;
    private final String fromEmail;

    public EmailService(ObjectProvider<JavaMailSender> mailSenderProvider,
                        @Value("${app.smtp.host:}") String smtpHost,
                        @Value("${app.smtp.from:notifications@ts-sc.local}") String fromEmail) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.smtpHost = smtpHost;
        this.fromEmail = fromEmail;
    }

    public boolean send(SendEmailRequest request) {
        if (request.toEmail() == null || request.toEmail().isBlank()) return false;
        String text = request.bodyText() == null || request.bodyText().isBlank()
                ? request.bodyHtml().replace("<br>", "\n").replace("<p>", "").replace("</p>", "\n")
                : request.bodyText();
        if (smtpHost.isBlank() || mailSender == null) {
            log.info("[DEV EMAIL DISPATCH] to={} <{}>, subject={}\n{}", request.toName(), request.toEmail(), request.subject(), text);
            return true;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, "TS-SC Notifications");
            helper.setTo(request.toEmail());
            helper.setSubject(request.subject());
            helper.setText(text, request.bodyHtml());
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("Failed to deliver notification email to {}", request.toEmail(), e);
            return false;
        }
    }
}
