package com.guicedee.activitymaster.notifications;

import com.google.inject.Inject;
import com.guicedee.activitymaster.fsdm.client.services.SessionUtils;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.notifications.NotificationModels.Channel;
import com.guicedee.activitymaster.notifications.NotificationModels.Counts;
import com.guicedee.activitymaster.notifications.NotificationModels.Delivery;
import com.guicedee.activitymaster.notifications.NotificationModels.Notification;
import com.guicedee.activitymaster.notifications.NotificationModels.Page;
import com.guicedee.activitymaster.notifications.NotificationModels.Publish;
import com.guicedee.activitymaster.notifications.NotificationModels.Published;
import com.guicedee.activitymaster.notifications.NotificationModels.Severity;
import com.guicedee.activitymaster.notifications.NotificationModels.State;
import com.guicedee.activitymaster.notifications.channels.NotificationDispatch;
import com.guicedee.activitymaster.notifications.channels.NotificationDispatcher;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Captures the trusted host identity and keeps it bound to the whole FSDM operation.
 * <p>
 * This is the only place a session is opened. The REST layer above it never sees a session, a
 * token or an identity, which is what stops a path or body parameter from ever standing in for the
 * authenticated caller.
 */
public final class NotificationApi
{
	private final NotificationIdentityProvider identities;
	private final INotificationService service;
	private final NotificationDispatcher dispatcher;

	@Inject
	public NotificationApi(NotificationIdentityProvider identities, INotificationService service,
	                       NotificationDispatcher dispatcher)
	{
		this.identities = identities;
		this.service = service;
		this.dispatcher = dispatcher;
	}

	@FunctionalInterface
	private interface Work<T>
	{
		Uni<T> run(Mutiny.StatelessSession session, ISystems<?, ?> system, NotificationIdentity identity);
	}

	private <T> Uni<T> execute(String enterprise, Work<T> work)
	{
		if (enterprise == null || enterprise.isBlank())
		{
			return Uni.createFrom()
			          .failure(new IllegalArgumentException("Enterprise required"));
		}
		return Uni.createFrom()
		          .deferred(identities::current)
		          .onItem()
		          .ifNull()
		          .failWith(() -> new SecurityException("Authenticated notification identity required"))
		          .chain(identity -> SessionUtils.withActivityMaster(enterprise, NotificationSystem.NAME, tuple -> {
			          if (!identity.enterpriseId()
			                       .equals(tuple.getItem2()
			                                    .getId()))
			          {
				          return Uni.createFrom()
				                    .failure(new SecurityException("Notification enterprise scope mismatch"));
			          }
			          return work.run(tuple.getItem1(), tuple.getItem3(), identity);
		          }));
	}

	/**
	 * Publishes a notification, then dispatches it on the requested channels.
	 * <p>
	 * The returned {@code Uni} completes as soon as the notification is durable. Channel delivery is
	 * started afterwards and does not delay, block or fail the response; its outcome is recorded and
	 * readable through {@link #deliveries}.
	 *
	 * @param enterprise the enterprise name
	 * @param request    the notification to publish
	 * @return the notification id and the recipients actually addressed
	 */
	public Uni<Published> publish(String enterprise, Publish request)
	{
		return Uni.createFrom()
		          .deferred(identities::current)
		          .onItem()
		          .ifNull()
		          .failWith(() -> new SecurityException("Authenticated notification identity required"))
		          .chain(identity -> SessionUtils.<Published>withActivityMaster(enterprise, NotificationSystem.NAME,
				          tuple -> {
					          if (!identity.enterpriseId()
					                       .equals(tuple.getItem2()
					                                    .getId()))
					          {
						          return Uni.createFrom()
						                    .failure(new SecurityException("Notification enterprise scope mismatch"));
					          }
					          return service.publish(tuple.getItem1(), tuple.getItem3(), identity, request);
				          })
		                                         .invoke(published -> dispatch(enterprise, identity, request,
				                                         published)));
	}

	private void dispatch(String enterprise, NotificationIdentity identity, Publish request, Published published)
	{
		List<Channel> channels = request.channels();
		if (channels.isEmpty() || published.recipients()
		                                   .isEmpty())
		{
			return;
		}
		OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
		Severity severity = request.severity() == null ? Severity.INFO : request.severity();
		List<NotificationDispatch> dispatches = new ArrayList<>(published.recipients()
		                                                                 .size());
		for (UUID recipient : published.recipients())
		{
			dispatches.add(new NotificationDispatch(identity.enterpriseId(), enterprise, published.id(), recipient,
					request.category(), severity, request.subject(), request.body(), request.data(), createdAt));
		}
		SessionUtils.fireAndForget(dispatcher.dispatch(enterprise, identity, channels, dispatches),
				"notification " + published.id() + " dispatch");
	}

	/**
	 * Reads one notification addressed to the caller.
	 *
	 * @param enterprise the enterprise name
	 * @param id         the notification id
	 * @return the notification, including its body
	 */
	public Uni<Notification> find(String enterprise, UUID id)
	{
		return execute(enterprise, (session, system, identity) -> service.find(session, system, identity, id));
	}

	/**
	 * Lists notifications addressed to the caller.
	 *
	 * @param enterprise the enterprise name
	 * @param state      an optional state filter
	 * @param category   an optional exact category filter
	 * @param offset     0..10,000
	 * @param limit      1..100
	 * @return a bounded page
	 */
	public Uni<Page<Notification>> list(String enterprise, State state, String category, int offset, int limit)
	{
		return execute(enterprise,
				(session, system, identity) -> service.list(session, system, identity, state, category, offset,
						limit));
	}

	/**
	 * Counts the caller's unread and undismissed notifications.
	 *
	 * @param enterprise the enterprise name
	 * @return the counts
	 */
	public Uni<Counts> counts(String enterprise)
	{
		return execute(enterprise, (session, system, identity) -> service.counts(session, system, identity));
	}

	/**
	 * Records a state transition for the caller.
	 *
	 * @param enterprise the enterprise name
	 * @param id         the notification id
	 * @param state      the new state
	 * @return the updated notification
	 */
	public Uni<Notification> transition(String enterprise, UUID id, State state)
	{
		return execute(enterprise,
				(session, system, identity) -> service.transition(session, system, identity, id, state));
	}

	/**
	 * Marks every unread notification addressed to the caller as read.
	 *
	 * @param enterprise the enterprise name
	 * @param category   an optional exact category filter
	 * @return how many notifications changed state
	 */
	public Uni<Long> readAll(String enterprise, String category)
	{
		return execute(enterprise, (session, system, identity) -> service.readAll(session, system, identity,
				category));
	}

	/**
	 * Lists the recorded delivery attempts for a notification.
	 *
	 * @param enterprise the enterprise name
	 * @param id         the notification id
	 * @param offset     0..10,000
	 * @param limit      1..100
	 * @return a bounded page of attempts
	 */
	public Uni<Page<Delivery>> deliveries(String enterprise, UUID id, int offset, int limit)
	{
		return execute(enterprise,
				(session, system, identity) -> service.deliveries(session, system, identity, id, offset, limit));
	}
}
