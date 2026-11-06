package io.openchain.alerting.notify.connector;

import io.openchain.alerting.notify.DeliveryResult;
import io.openchain.alerting.notify.Notification;
import io.openchain.alerting.notify.NotificationConnector;
import io.openchain.alerting.notify.NotificationFormatter;
import io.openchain.alerting.rule.ActionType;
import java.util.List;
import java.util.regex.Pattern;

/** E-mail channel. Transport is delegated to an {@link EmailSender} (SMTP/SES/... provided by oci-app). */
public final class EmailConnector implements NotificationConnector {

    /** Transport port. Implementations return normally on acceptance and throw on failure. */
    public interface EmailSender {
        void send(EmailMessage message) throws Exception;
    }

    public record EmailMessage(String from, List<String> to, String subject, String textBody, String idempotencyId) {
        public EmailMessage { to = List.copyOf(to); }
    }

    /** Stub sender that records messages in memory (dev/tests: no SMTP). */
    public static final class InMemoryEmailSender implements EmailSender {
        private final java.util.List<EmailMessage> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public void send(EmailMessage m) { sent.add(m); }
        public List<EmailMessage> sent() { return List.copyOf(sent); }
    }

    private static final Pattern ADDRESS = Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,63}$");

    private final String id;
    private final EmailSender sender;
    private final String from;
    private final List<String> to;

    public EmailConnector(String id, EmailSender sender, String from, List<String> to) {
        if (!ADDRESS.matcher(from).matches()) throw new IllegalArgumentException("invalid from address");
        if (to.isEmpty() || to.size() > 50 || !to.stream().allMatch(a -> ADDRESS.matcher(a).matches()))
            throw new IllegalArgumentException("invalid recipient list");
        this.id = id;
        this.sender = sender;
        this.from = from;
        this.to = List.copyOf(to);
    }

    @Override public String id() { return id; }
    @Override public ActionType type() { return ActionType.EMAIL; }

    @Override public DeliveryResult send(Notification n) {
        String subject = ("[OpenChain " + n.severity() + "] " + n.title()).replaceAll("[\\r\\n]+", " ");
        if (subject.length() > 200) subject = subject.substring(0, 200);
        try {
            sender.send(new EmailMessage(from, to, subject, NotificationFormatter.text(n, 20_000), n.deliveryId()));
            return DeliveryResult.ok(0);
        } catch (Exception e) {
            return DeliveryResult.retry(0, "email transport: " + e.getMessage());
        }
    }
}
