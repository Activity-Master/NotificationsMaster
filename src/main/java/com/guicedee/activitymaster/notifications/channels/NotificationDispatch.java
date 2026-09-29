package com.guicedee.activitymaster.notifications.channels;

import com.guicedee.activitymaster.notifications.NotificationModels.DeliveryResult;
import com.guicedee.activitymaster.notifications.NotificationModels.Severity;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * What a channel is handed for one recipient of one notification.
 * <p>
 * This is a snapshot taken after the publishing transaction committed. It deliberately carries no
 * session, no token and no identity: a channel performs external I/O and must not be able to reach
 * the database or act as the caller.
 *
 * @param enterpriseId   the enterprise the notification belongs to
 * @param enterpriseName the enterprise name, for addressing and logging
 * @param notificationId the notification event id
 * @param recipientId    the addressed party
 * @param category       the caller-defined grouping
 * @param severity       the declared severity
 * @param subject        the summary line
 * @param body           the plain Unicode body
 * @param data           the structured payload as JSON text, or {@code null}
 * @param createdAt      when the notification was published
 */
public record NotificationDispatch(UUID enterpriseId, String enterpriseName, UUID notificationId, UUID recipientId,
                                   String category, Severity severity, String subject, String body, String data,
                                   OffsetDateTime createdAt)
{
	public NotificationDispatch
	{
		Objects.requireNonNull(enterpriseId, "enterpriseId");
		Objects.requireNonNull(notificationId, "notificationId");
		Objects.requireNonNull(recipientId, "recipientId");
	}

	/**
	 * The outcome of one delivery attempt.
	 *
	 * @param result the outcome
	 * @param detail a short, non-sensitive explanation recorded against the attempt
	 */
	public record Outcome(DeliveryResult result, String detail)
	{
		/**
		 * @param detail what was delivered, or where
		 * @return a successful outcome
		 */
		public static Outcome sent(String detail)
		{
			return new Outcome(DeliveryResult.SENT, detail);
		}

		/**
		 * @param detail why the channel had nothing to do
		 * @return a skipped outcome
		 */
		public static Outcome skipped(String detail)
		{
			return new Outcome(DeliveryResult.SKIPPED, detail);
		}

		/**
		 * @param detail why the attempt failed
		 * @return a failed outcome
		 */
		public static Outcome failed(String detail)
		{
			return new Outcome(DeliveryResult.FAILED, detail);
		}
	}
}
