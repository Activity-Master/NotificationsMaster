package com.guicedee.activitymaster.notifications;

import com.google.inject.PrivateModule;
import com.google.inject.Singleton;
import com.guicedee.activitymaster.notifications.channels.EventBusNotificationChannel;
import com.guicedee.activitymaster.notifications.channels.NotificationDispatcher;
import com.guicedee.activitymaster.notifications.channels.WebhookNotificationChannel;
import com.guicedee.client.services.lifecycle.IGuiceModule;
import lombok.extern.log4j.Log4j2;

/**
 * Binds the notification service, the dispatcher and the built-in channels.
 * <p>
 * A private module keeps the FSDM implementation off the application injector: only the service
 * contract and the identity-capturing API are exposed, so nothing outside this module can obtain a
 * handle that writes notification rows without going through an authenticated identity.
 */
@Log4j2
public final class NotificationModule extends PrivateModule implements IGuiceModule<NotificationModule>
{
	@Override
	protected void configure()
	{
		log.info("🔔 Using Notification Activity Master Module and routes at /rest/{enterprise}/notifications");
		bind(INotificationService.class).to(NotificationService.class)
		                                .in(Singleton.class);
		expose(INotificationService.class);

		bind(NotificationDispatcher.class).in(Singleton.class);
		expose(NotificationDispatcher.class);

		bind(NotificationApi.class).in(Singleton.class);
		expose(NotificationApi.class);

		// Channels are resolved through IGuiceContext by the dispatcher's ServiceLoader sweep, so they
		// are exposed as well as bound. The mail channel is deliberately absent: Mail Master is an
		// optional dependency, and an explicit binding here would fail injector boot on a deployment
		// without it. The dispatcher just-in-time binds it instead, and tolerates its absence.
		bind(WebhookNotificationChannel.class).in(Singleton.class);
		expose(WebhookNotificationChannel.class);
		bind(EventBusNotificationChannel.class).in(Singleton.class);
		expose(EventBusNotificationChannel.class);
	}
}
