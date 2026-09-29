package com.guicedee.activitymaster.notifications.channels;

import com.google.inject.Inject;
import com.guicedee.activitymaster.fsdm.client.services.SessionUtils;
import com.guicedee.activitymaster.notifications.INotificationService;
import com.guicedee.activitymaster.notifications.NotificationIdentity;
import com.guicedee.activitymaster.notifications.NotificationModels.Channel;
import com.guicedee.activitymaster.notifications.NotificationSystem;
import com.guicedee.client.IGuiceContext;
import io.smallrye.mutiny.Uni;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;

/**
 * Fans a published notification out to its channels and records what happened.
 * <p>
 * Dispatch runs <em>after</em> the publishing transaction has committed, deliberately. Holding a
 * database transaction open across an SMTP conversation or an HTTPS round trip would let a slow
 * third party pin a connection for the length of its timeout, and a rollback would then leave a
 * notification that was already emailed. So publish returns as soon as the notification is durable,
 * and delivery is asynchronous and observable through the deliveries endpoint.
 * <p>
 * Delivery for one recipient is sequential across channels, and each recipient's attempts are
 * written in a single transaction of their own. One recipient's failure never affects another's.
 */
@Log4j2
public class NotificationDispatcher
{
	@Inject
	private INotificationService service;

	/**
	 * Delivers a notification on every requested channel and records each attempt.
	 *
	 * @param enterpriseName the enterprise name, used to open the recording sessions
	 * @param identity       the publishing identity, reused to attribute the attempts
	 * @param channels       the channels requested by the publisher
	 * @param dispatches     one snapshot per recipient
	 * @return a Uni completing when every attempt has been recorded
	 */
	public Uni<Void> dispatch(String enterpriseName, NotificationIdentity identity, List<Channel> channels,
	                          List<NotificationDispatch> dispatches)
	{
		Map<Channel, INotificationChannel<?>> available = channels();
		List<INotificationChannel<?>> selected = new ArrayList<>();
		for (Channel requested : channels)
		{
			if (requested == Channel.STORE)
			{
				continue;
			}
			INotificationChannel<?> channel = available.get(requested);
			if (channel == null)
			{
				log.warn("No notification channel implementation is registered for {}", requested);
				continue;
			}
			selected.add(channel);
		}
		if (selected.isEmpty() || dispatches.isEmpty())
		{
			return Uni.createFrom()
			          .voidItem();
		}
		Uni<Void> chain = Uni.createFrom()
		                     .voidItem();
		for (NotificationDispatch dispatch : dispatches)
		{
			chain = chain.chain(() -> deliverAll(selected, dispatch).chain(
					                             outcomes -> record(enterpriseName, identity, dispatch, outcomes))
			                                                        .onFailure()
			                                                        .recoverWithItem(failure -> {
				                                                        log.error(
						                                                        "Notification {} dispatch failed for {}: {}",
						                                                        dispatch.notificationId(),
						                                                        dispatch.recipientId(),
						                                                        failure.getMessage(), failure);
				                                                        return null;
			                                                        })
			                                                        .replaceWithVoid());
		}
		return chain;
	}

	private Uni<Map<Channel, NotificationDispatch.Outcome>> deliverAll(List<INotificationChannel<?>> channels,
	                                                                   NotificationDispatch dispatch)
	{
		Uni<Map<Channel, NotificationDispatch.Outcome>> chain = Uni.createFrom()
		                                                           .item(new LinkedHashMap<>());
		for (INotificationChannel<?> channel : channels)
		{
			chain = chain.chain(outcomes -> channel.deliver(dispatch)
			                                       // A channel that breaks its contract and fails the Uni is
			                                       // still recorded, so the attempt is never invisible.
			                                       .onFailure()
			                                       .recoverWithItem(failure -> NotificationDispatch.Outcome.failed(
					                                       failure.getClass()
					                                              .getSimpleName()))
			                                       .map(outcome -> {
				                                       outcomes.put(channel.channel(), outcome);
				                                       return outcomes;
			                                       }));
		}
		return chain;
	}

	private Uni<Void> record(String enterpriseName, NotificationIdentity identity, NotificationDispatch dispatch,
	                         Map<Channel, NotificationDispatch.Outcome> outcomes)
	{
		if (outcomes.isEmpty())
		{
			return Uni.createFrom()
			          .voidItem();
		}
		UUID notification = dispatch.notificationId();
		UUID recipient = dispatch.recipientId();
		return SessionUtils.<Void>withActivityMaster(enterpriseName, NotificationSystem.NAME, tuple -> {
			Uni<Void> chain = Uni.createFrom()
			                     .voidItem();
			for (Map.Entry<Channel, NotificationDispatch.Outcome> entry : outcomes.entrySet())
			{
				chain = chain.chain(() -> service.recordDelivery(tuple.getItem1(), tuple.getItem3(), identity,
						                                 notification, recipient, entry.getKey(), entry.getValue()
						                                                                               .result(),
						                                 entry.getValue()
						                                      .detail())
				                                 .replaceWithVoid());
			}
			return chain;
		});
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private Map<Channel, INotificationChannel<?>> channels()
	{
		List<INotificationChannel> loaded = new ArrayList<>();
		// stream() hands back a provider per declaration without instantiating it, so an optional
		// channel whose dependency is absent fails on its own get() instead of aborting the sweep
		// and taking every other channel down with it.
		ServiceLoader.load(INotificationChannel.class)
		             .stream()
		             .forEach(provider -> {
			             try
			             {
				             loaded.add((INotificationChannel) IGuiceContext.get(provider.type()));
			             }
			             catch (Throwable unavailable)
			             {
				             log.warn("Notification channel {} is unavailable and will be skipped: {}",
						             provider.type()
						                     .getName(), unavailable.toString());
			             }
		             });
		loaded.sort(INotificationChannel::compareTo);
		Map<Channel, INotificationChannel<?>> byChannel = new LinkedHashMap<>();
		for (INotificationChannel<?> channel : loaded)
		{
			// First registration wins, so a product can replace a built-in channel with a
			// lower-sorted implementation of its own.
			byChannel.putIfAbsent(channel.channel(), channel);
		}
		return byChannel;
	}
}
