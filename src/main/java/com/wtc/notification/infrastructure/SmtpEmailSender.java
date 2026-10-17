package com.wtc.notification.infrastructure;

import com.wtc.notification.application.Channel;
import com.wtc.notification.application.NotificationMessage;
import com.wtc.notification.application.NotificationSender;
import com.wtc.notification.application.Rendered;
import com.wtc.notification.application.SendResult;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Courriel par SMTP (Mailjet, SES, Postfix…). Un refus d'adresse ou un message mal formé est
 * définitif ; une panne de connexion ou d'authentification passagère se réessaie.
 */
final class SmtpEmailSender implements NotificationSender {

    private final JavaMailSender mailSender;
    private final String from;

    SmtpEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public SendResult send(NotificationMessage message, Rendered content) {
        try {
            InternetAddress.parse(
                    message.recipient(),
                    true); // adresse invalide : définitif, avant tout appel réseau
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(InternetAddress.parse(from)[0]);
            helper.setTo(message.recipient());
            helper.setSubject(content.subject());
            helper.setText(
                    content.text(), content.html() == null ? content.text() : content.html());
            if (content.html() == null) {
                helper.setText(content.text(), false);
            }
            mailSender.send(mime);
            return SendResult.sent();
        } catch (MailParseException | jakarta.mail.internet.AddressException e) {
            return SendResult.permanent(
                    "Message ou adresse invalide : " + e.getClass().getSimpleName());
        } catch (MailAuthenticationException e) {
            return SendResult.retry("Authentification SMTP refusée (configuration ?)");
        } catch (MailSendException e) {
            return SendResult.retry("Envoi SMTP échoué : " + root(e));
        } catch (MailException | jakarta.mail.MessagingException e) {
            return SendResult.retry("Erreur SMTP : " + e.getClass().getSimpleName());
        }
    }

    private static String root(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName();
    }
}
