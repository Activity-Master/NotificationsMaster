package com.guicedee.activitymaster.notifications.channels;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.guicedee.activitymaster.notifications.NotificationModels.Channel;
import com.guicedee.vertx.spi.VertXPreStartup;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import lombok.extern.log4j.Log4j2;

/**
 * Publishes a notification onto the Vert.x event bus for live delivery.
 * <p>
 * The address is per enterprise, so a bridge only ever forwards traffic for the tenant it is bound
 * to. Nothing leaves the process here: the host is responsible for bridging the bus to websocket or
 * SSE sessions, and for checking that the connected session belongs to {@code recipientId} before
 * forwarding. Publishing to the bus is not an authorisation decision.
 */
@Log4j2
public class EventBusNotificationChannel implements INotificationChannel<EventBusNotificationChannel>
{
	@Inject
	private Provider<INotificationTransport> transport;

	@Override
	public Channel channel()
	{
		return Channel.EVENT_BUS;
	}

	@Override
	public Integer sortOrder()
	{
		return 50;
	}

	@Override
	public Uni<NotificationDispatch.Outcome> deliver(NotificationDispatch dispatch)
	{
		return Uni.createFrom()
		          .item(() -> {
			          String address = transport.get()
			                                    .eventBusAddress(dispatch.enterpriseId());
			          JsonObject payload = new JsonObject().put("notificationId", dispatch.notificationId()
			                                                                              .toString())
			                                               .put("enterpriseId", dispatch.enterpriseId()
			                                                                            .toString())
			                                               .put("recipientId", dispatch.recipientId()
			                                                                           .toString())
			                                               .put("category", dispatch.category())
			                                               .put("severity", dispatch.severity()
			                                                                        .name())
			                                               .put("subject", dispatch.subject())
			                                               .put("body", dispatch.body());
			          if (dispatch.data() != null)
			          {
				          payload.put("data", dispatch.data());
			          }
			          if (dispatch.createdAt() != null)
			          {
				          payload.put("createdAt", dispatch.createdAt()
				                                           .toString());
			          }
			          VertXPreStartup.getVertx()
			                         .eventBus()
			                         .publish(address, payload);
			          return NotificationDispatch.Outcome.sent("Published to " + address);
		          })
		          .onFailure()
		          .recoverWithItem(failure -> {
			          log.warn("Notification {} event bus publish failed for {}: {}", dispatch.notificationId(),
					          dispatch.recipientId(), failure.getMessage());
			          String message = failure.getMessage();
			          return NotificationDispatch.Outcome.failed(failure.getClass()
			                                                            .getSimpleName()
					          + (message == null ? "" : ": " + message));
		          });
	}
}
