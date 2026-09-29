package com.guicedee.activitymaster.notifications.channels;

import com.guicedee.activitymaster.notifications.NotificationModels.Channel;
import com.guicedee.client.services.IDefaultService;
import io.smallrye.mutiny.Uni;

/**
 * Delivers a notification somewhere other than the FSDM store.
 * <p>
 * Channels are discovered through {@link java.util.ServiceLoader}, resolved through Guice and
 * consulted in {@link IDefaultService#sortOrder() sort order}. A channel runs <em>after</em> the
 * publishing transaction has committed, on its own, and must never throw: an unreachable SMTP
 * server or a webhook timeout is a recorded {@code FAILED} attempt, not a failed publish.
 * <p>
 * A channel that has nothing configured for a recipient returns
 * {@link NotificationDispatch.Outcome#skipped}, which is recorded and is not an error.
 *
 * @param <J> the concrete implementation type
 */
public interface INotificationChannel<J extends INotificationChannel<J>> extends IDefaultService<J>
{
	/**
	 * @return the channel this implementation serves
	 */
	Channel channel();

	/**
	 * Delivers one notification to one recipient.
	 *
	 * @param dispatch the committed snapshot to deliver
	 * @return the outcome; a failure on this {@code Uni} is recorded as {@code FAILED}
	 */
	Uni<NotificationDispatch.Outcome> deliver(NotificationDispatch dispatch);
}
