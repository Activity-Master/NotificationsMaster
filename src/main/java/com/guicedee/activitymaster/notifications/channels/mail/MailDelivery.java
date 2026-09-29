package com.guicedee.activitymaster.notifications.channels.mail;

import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.guicedee.activitymaster.mail.services.IMailTransportService;
import com.guicedee.activitymaster.mail.services.dto.MailMessage;
import com.guicedee.activitymaster.notifications.channels.NotificationDispatch;
import com.guicedee.client.IGuiceContext;
import io.smallrye.mutiny.Uni;
import lombok.extern.log4j.Log4j2;

/**
 * Everything in the mail channel that touches a Mail Master type.
 * <p>
 * Split out of {@link MailNotificationChannel} on purpose: the JVM verifies a method the first time
 * it runs, so a single method mentioning {@code IMailTransportService} would fail to verify on a
 * deployment without Mail Master even if a guard meant it never executed. Keeping those references
 * in a separate class means this one is only ever loaded once Mail Master is known to be present.
 * <p>
 * The body is sent as plain text only. A notification body is arbitrary Unicode from a publishing
 * system, and rendering it as HTML would make every publisher a potential injector into a
 * recipient's mail client.
 */
@Log4j2
final class MailDelivery
{
	private MailDelivery()
	{
	}

	static Uni<NotificationDispatch.Outcome> deliver(NotificationDispatch dispatch)
	{
		IMailNotificationTransport transport = IGuiceContext.get(IMailNotificationTransport.class);
		return transport.mail(dispatch.enterpriseId(), dispatch.recipientId())
		                .chain(target -> {
			                if (target.isEmpty())
			                {
				                return Uni.createFrom()
				                          .item(NotificationDispatch.Outcome.skipped("No mail target configured"));
			                }
			                return send(target.get(), dispatch);
		                })
		                .onFailure()
		                .recoverWithItem(failure -> NotificationDispatch.Outcome.failed(reason(failure)));
	}

	private static Uni<NotificationDispatch.Outcome> send(IMailNotificationTransport.MailTarget destination,
	                                                      NotificationDispatch dispatch)
	{
		MailMessage message = new MailMessage();
		if (destination.fromName() == null || destination.fromName()
		                                                 .isBlank())
		{
			message.from(destination.fromAddress());
		}
		else
		{
			message.from(destination.fromName(), destination.fromAddress());
		}
		message.addTo(destination.toAddress());
		message.setSubject(dispatch.subject());
		message.setTextBody(dispatch.body());

		return transport().send(destination.server(), message)
		                  .map(id -> NotificationDispatch.Outcome.sent("Accepted by SMTP as " + id))
		                  .onFailure()
		                  .recoverWithItem(failure -> {
			                  log.warn("Notification {} mail delivery failed for {}: {}", dispatch.notificationId(),
					                  dispatch.recipientId(), failure.getMessage());
			                  return NotificationDispatch.Outcome.failed(reason(failure));
		                  });
	}

	private static IMailTransportService<?> transport()
	{
		return IGuiceContext.get(Key.get(new TypeLiteral<IMailTransportService<?>>()
		{
		}));
	}

	/**
	 * Delivery detail is stored and later shown through the audit endpoint, so it carries the failure
	 * class and message only — never a stack trace, a credential or a full address.
	 */
	private static String reason(Throwable failure)
	{
		String message = failure.getMessage();
		return failure.getClass()
		              .getSimpleName() + (message == null ? "" : ": " + message);
	}
}
