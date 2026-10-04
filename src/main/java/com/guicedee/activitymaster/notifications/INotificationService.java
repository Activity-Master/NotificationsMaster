package com.guicedee.activitymaster.notifications;

import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.notifications.NotificationModels.Counts;
import com.guicedee.activitymaster.notifications.NotificationModels.Delivery;
import com.guicedee.activitymaster.notifications.NotificationModels.DeliveryResult;
import com.guicedee.activitymaster.notifications.NotificationModels.Notification;
import com.guicedee.activitymaster.notifications.NotificationModels.Page;
import com.guicedee.activitymaster.notifications.NotificationModels.Publish;
import com.guicedee.activitymaster.notifications.NotificationModels.Published;
import com.guicedee.activitymaster.notifications.NotificationModels.State;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;

import java.util.UUID;

/**
 * Caller owns the stateless session and transaction; actor identity comes from the host.
 * <p>
 * Publishing requires the {@code notifications.publish} behaviour grant. Every other operation acts
 * only on notifications addressed to the calling party: a recipient can read and change state on
 * their own notifications and nothing else, and reading someone else's notification is a 404 rather
 * than a 403, so the API never confirms that a record exists.
 */
public interface INotificationService
{
    /** Counts unread across the realm inbox, stopping at 100 for a 99+ badge. */
    Uni<Long> unreadBadge(Mutiny.StatelessSession session, ISystems<?, ?> system, NotificationIdentity identity);
	/** Complete recipient history, including dismissed messages, using a stable date/ID cursor. */
	Uni<NotificationModels.History> history(Mutiny.StatelessSession session, ISystems<?, ?> system,
	        NotificationIdentity identity, java.time.OffsetDateTime beforeTime, UUID beforeId, int limit);

	/** Behaviour grant a publisher must hold. */
	String PUBLISH_BEHAVIOR = "notifications.publish";

	/** Behaviour grant required to inspect delivery attempts. */
	String AUDIT_BEHAVIOR = "notifications.audit";

	/** Scoped provider name used by the FSDM behaviour authority. */
	String PROVIDER = "notifications";

	/**
	 * Publishes a notification to one or more recipients. Requires {@link #PUBLISH_BEHAVIOR}.
	 *
	 * @param session  the caller session, inside the caller transaction
	 * @param system   the Notification Master system
	 * @param identity the verified publisher
	 * @param request  the notification to publish
	 * @return the notification id and the recipients actually addressed
	 */
	Uni<Published> publish(Mutiny.StatelessSession session, ISystems<?, ?> system, NotificationIdentity identity,
	                       Publish request);

	/**
	 * Reads one notification addressed to the caller, including its body.
	 *
	 * @param session  the caller session
	 * @param system   the Notification Master system
	 * @param identity the verified recipient
	 * @param id       the notification id
	 * @return the notification
	 */
	Uni<Notification> find(Mutiny.StatelessSession session, ISystems<?, ?> system, NotificationIdentity identity,
	                       UUID id);

	/**
	 * Lists notifications addressed to the caller, newest first. Bodies are omitted.
	 *
	 * @param session  the caller session
	 * @param system   the Notification Master system
	 * @param identity the verified recipient
	 * @param state    an optional state filter; {@code null} returns everything but dismissed
	 * @param category an optional exact category filter
	 * @param offset   0..10,000
	 * @param limit    1..100
	 * @return a bounded page
	 */
	Uni<Page<Notification>> list(Mutiny.StatelessSession session, ISystems<?, ?> system,
	                             NotificationIdentity identity, State state, String category, int offset, int limit);

	/**
	 * Counts the caller's unread and undismissed notifications.
	 *
	 * @param session  the caller session
	 * @param system   the Notification Master system
	 * @param identity the verified recipient
	 * @return the counts
	 */
	Uni<Counts> counts(Mutiny.StatelessSession session, ISystems<?, ?> system, NotificationIdentity identity);

	/**
	 * Records a state transition for the caller on one of their notifications.
	 * <p>
	 * Transitions are append-only and idempotent: recording a state the caller already holds is a
	 * no-op that returns the current view. {@link State#UNREAD} cannot be recorded, because it is the
	 * absence of a state rather than a state.
	 *
	 * @param session  the caller session, inside the caller transaction
	 * @param system   the Notification Master system
	 * @param identity the verified recipient
	 * @param id       the notification id
	 * @param state    the new state
	 * @return the updated notification
	 */
	Uni<Notification> transition(Mutiny.StatelessSession session, ISystems<?, ?> system,
	                             NotificationIdentity identity, UUID id, State state);

	/**
	 * Marks every currently unread notification addressed to the caller as read.
	 *
	 * @param session  the caller session, inside the caller transaction
	 * @param system   the Notification Master system
	 * @param identity the verified recipient
	 * @param category an optional exact category filter
	 * @return how many notifications changed state
	 */
	Uni<Long> readAll(Mutiny.StatelessSession session, ISystems<?, ?> system, NotificationIdentity identity,
	                  String category);

	/**
	 * Lists the delivery attempts recorded for a notification. Requires {@link #AUDIT_BEHAVIOR}.
	 *
	 * @param session  the caller session
	 * @param system   the Notification Master system
	 * @param identity the verified auditor
	 * @param id       the notification id
	 * @param offset   0..10,000
	 * @param limit    1..100
	 * @return a bounded page of attempts
	 */
	Uni<Page<Delivery>> deliveries(Mutiny.StatelessSession session, ISystems<?, ?> system,
	                               NotificationIdentity identity, UUID id, int offset, int limit);

	/**
	 * Records the outcome of one channel delivery attempt.
	 * <p>
	 * Called by the dispatcher after the publishing transaction has committed, never by a client.
	 * The publisher identity is reused so the attempt is attributed to the system that published.
	 *
	 * @param session     the dispatcher session, inside its own transaction
	 * @param system      the Notification Master system
	 * @param identity    the publishing identity
	 * @param id          the notification id
	 * @param recipientId the addressed party
	 * @param channel     the channel attempted
	 * @param result      the outcome
	 * @param detail      a short, non-sensitive explanation
	 * @return the recorded attempt
	 */
	Uni<Delivery> recordDelivery(Mutiny.StatelessSession session, ISystems<?, ?> system,
	                             NotificationIdentity identity, UUID id, UUID recipientId,
	                             NotificationModels.Channel channel, DeliveryResult result, String detail);
}
