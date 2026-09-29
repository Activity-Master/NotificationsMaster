package com.guicedee.activitymaster.notifications.channels.mail;

import com.guicedee.activitymaster.notifications.NotificationModels.Channel;
import com.guicedee.activitymaster.notifications.channels.INotificationChannel;
import com.guicedee.activitymaster.notifications.channels.NotificationDispatch;
import io.smallrye.mutiny.Uni;
import lombok.extern.log4j.Log4j2;

/**
 * Delivers a notification by email through Mail Master, when Mail Master is present.
 * <p>
 * Mail Master is an optional dependency ({@code requires static}), so this class deliberately
 * mentions no Mail Master type at all. Everything that touches one lives in {@link MailDelivery},
 * which is only referenced after {@link #AVAILABLE} has been confirmed — so on a deployment
 * without Mail Master this class still loads and verifies, and simply reports every dispatch as
 * {@code SKIPPED} instead of breaking the channel sweep.
 */
@Log4j2
public class MailNotificationChannel implements INotificationChannel<MailNotificationChannel>
{
	/** Whether Mail Master is on the module path and readable from here. */
	public static final boolean AVAILABLE = probe();

	@Override
	public Channel channel()
	{
		return Channel.MAIL;
	}

	@Override
	public Integer sortOrder()
	{
		return 100;
	}

	@Override
	public Uni<NotificationDispatch.Outcome> deliver(NotificationDispatch dispatch)
	{
		if (!AVAILABLE)
		{
			return Uni.createFrom()
			          .item(NotificationDispatch.Outcome.skipped("Mail Master is not installed"));
		}
		return MailDelivery.deliver(dispatch);
	}

	private static boolean probe()
	{
		try
		{
			Class.forName("com.guicedee.activitymaster.mail.services.IMailTransportService", false,
					MailNotificationChannel.class.getClassLoader());
			return true;
		}
		catch (Throwable absent)
		{
			log.info("🔔 Mail Master is not installed; the notification mail channel will skip every dispatch");
			return false;
		}
	}
}
