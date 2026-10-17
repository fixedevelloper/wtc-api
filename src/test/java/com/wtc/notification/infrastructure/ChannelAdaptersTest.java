package com.wtc.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpServer;
import com.wtc.notification.application.Channel;
import com.wtc.notification.application.NotificationMessage;
import com.wtc.notification.application.Rendered;
import com.wtc.notification.application.SendResult.Kind;
import com.wtc.shared.config.WtcProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import tools.jackson.databind.json.JsonMapper;

class ChannelAdaptersTest {

    @RegisterExtension
    static GreenMailExtension mail = new GreenMailExtension(ServerSetupTest.SMTP);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                ex -> {
                    requests.add(
                            ex.getRequestURI()
                                    + " auth="
                                    + ex.getRequestHeaders().getFirst("Authorization")
                                    + " "
                                    + new String(
                                            ex.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                    byte[] body = "{\"error\":\"x\"}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(status.get(), body.length);
                    ex.getResponseBody().write(body);
                    ex.close();
                });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static NotificationMessage msg(Channel c, String to) {
        return new NotificationMessage(1, c, to, "t", "{}", 1);
    }

    private SmtpEmailSender smtp() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(mail.getSmtp().getPort());
        return new SmtpEmailSender(sender, "WTC <no-reply@wtc.example>");
    }

    @Test
    void smtpSendsAMultipartMailWithAccentsAndRejectsABadAddressPermanently() throws Exception {
        Rendered content = new Rendered("Payé ✓", "Bonjour", "<p>Bonjour</p>", null, null);
        assertThat(smtp().send(msg(Channel.EMAIL, "awa@example.com"), content).kind())
                .isEqualTo(Kind.SENT);
        var received = mail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getSubject()).isEqualTo("Payé ✓");
        assertThat(received[0].getFrom()[0].toString()).contains("no-reply@wtc.example");

        assertThat(smtp().send(msg(Channel.EMAIL, "pas une adresse"), content).kind())
                .isEqualTo(Kind.PERMANENT);
        assertThat(mail.getReceivedMessages()).hasSize(1);
    }

    @Test
    void smtpOutageIsRetried() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(1); // personne n'écoute
        var down = new SmtpEmailSender(sender, "WTC <no-reply@wtc.example>");
        assertThat(
                        down.send(
                                        msg(Channel.EMAIL, "a@example.com"),
                                        new Rendered("s", "t", null, null, null))
                                .kind())
                .isEqualTo(Kind.RETRY);
    }

    @Test
    void whatsappPostsTheApprovedTemplateWithDigitsOnlyNumber() {
        var cfg = new WtcProperties.Whatsapp("waba", url(), "PNID", "SECRET", "fr");
        var sender = new WabaWhatsAppSender(cfg, JSON);
        var content =
                new Rendered(null, null, null, "wtc_transfer_completed", List.of("Awa", "REF"));
        assertThat(sender.send(msg(Channel.WHATSAPP, "+242 06 123 4567"), content).kind())
                .isEqualTo(Kind.SENT);
        String request = requests.get(0);
        assertThat(request).startsWith("/PNID/messages auth=Bearer SECRET");
        var body = JSON.readTree(request.substring(request.indexOf('{')));
        assertThat(body.get("to").asString()).isEqualTo("242061234567");
        assertThat(body.get("template").get("name").asString()).isEqualTo("wtc_transfer_completed");
        assertThat(body.get("template").get("components").get(0).get("parameters").size())
                .isEqualTo(2);
    }

    @Test
    void httpFailuresAreClassifiedRetryOrPermanent() {
        var sender =
                new TelegramBotSender(
                        new WtcProperties.Telegram("telegram", url(), "TOKEN", "-1"), JSON);
        var content = new Rendered(null, "alerte", null, null, null);
        var message = msg(Channel.TELEGRAM, "-100");
        assertThat(sender.send(message, content).kind()).isEqualTo(Kind.SENT);
        assertThat(requests.get(0)).startsWith("/botTOKEN/sendMessage");
        assertThat(requests.get(0)).contains("\"chat_id\":\"-100\"").contains("alerte");
        status.set(429);
        assertThat(sender.send(message, content).kind()).isEqualTo(Kind.RETRY);
        status.set(503);
        assertThat(sender.send(message, content).kind()).isEqualTo(Kind.RETRY);
        status.set(400);
        var permanent = sender.send(message, content);
        assertThat(permanent.kind()).isEqualTo(Kind.PERMANENT);
        server.stop(0);
        assertThat(sender.send(message, content).kind()).isEqualTo(Kind.RETRY);
    }

    @Test
    void realWhatsappAndTelegramRefuseToStartWithoutCredentials() {
        assertThatThrownBy(
                        () ->
                                new WabaWhatsAppSender(
                                        new WtcProperties.Whatsapp("waba", url(), null, null, "fr"),
                                        JSON))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                new TelegramBotSender(
                                        new WtcProperties.Telegram("telegram", url(), "", null),
                                        JSON))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new WtcProperties.Whatsapp("waba", url(), "P", "SECRET", "fr").toString())
                .doesNotContain("SECRET");
        assertThat(new WtcProperties.Telegram("telegram", url(), "TOKEN", "-1").toString())
                .doesNotContain("TOKEN");
    }
}
