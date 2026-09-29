package com.guicedee.activitymaster.notifications.channels;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.guicedee.activitymaster.notifications.NotificationModels.Channel;
import com.guicedee.client.Environment;
import com.guicedee.vertx.spi.VertXPreStartup;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import lombok.extern.log4j.Log4j2;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Delivers a notification by POSTing it to a recipient's webhook.
 * <p>
 * This is the only part of the module that makes outbound network calls, so it is the part that has
 * to be paranoid. The URL comes from {@link INotificationTransport}, never from a request; it must
 * be {@code https} with a host, and the channel re-checks that rather than trusting the binding.
 * Redirects are not followed, because a 302 is a way to turn an approved destination into an
 * arbitrary one. The response body is discarded and never surfaced, so a receiver cannot use it to
 * write into the audit trail.
 */
@Log4j2
public class WebhookNotificationChannel implements INotificationChannel<WebhookNotificationChannel>
{
	/** Environment key for the per-attempt webhook timeout, in milliseconds. */
	public static final String TIMEOUT_MILLIS = "NOTIFICATIONS_WEBHOOK_TIMEOUT_MS";

	/** Header carrying the shared secret, when the host supplies one. */
	public static final String TOKEN_HEADER = "X-ActivityMaster-Token";

	private final AtomicReference<WebClient> client = new AtomicReference<>();

	@Inject
	private Provider<INotificationTransport> transport;

	@Override
	public Channel channel()
	{
		return Channel.WEBHOOK;
	}

	@Override
	public Integer sortOrder()
	{
		return 200;
	}

	@Override
	public Uni<NotificationDispatch.Outcome> deliver(NotificationDispatch dispatch)
	{
		INotificationTransport settings = transport.get();
		return settings.webhook(dispatch.enterpriseId(), dispatch.recipientId())
		               .chain(target -> {
			               if (target.isEmpty())
			               {
				               return Uni.createFrom()
				                         .item(NotificationDispatch.Outcome.skipped("No webhook configured"));
			               }
			               URI url = target.get();
			               String rejection = reject(url);
			               if (rejection != null)
			               {
				               return Uni.createFrom()
				                         .item(NotificationDispatch.Outcome.failed(rejection));
			               }
			               return settings.webhookSecret(dispatch.enterpriseId(), dispatch.recipientId())
			                              .chain(secret -> post(url, secret.orElse(null), dispatch));
		               })
		               .onFailure()
		               .recoverWithItem(failure -> NotificationDispatch.Outcome.failed(reason(failure)));
	}

	private Uni<NotificationDispatch.Outcome> post(URI url, String secret, NotificationDispatch dispatch)
	{
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
		var request = client().postAbs(url.toString())
		                      .putHeader("Content-Type", "application/json; charset=utf-8")
		                      .timeout(timeout());
		if (secret != null && !secret.isBlank())
		{
			request.putHeader(TOKEN_HEADER, secret);
		}
		return Uni.createFrom()
		          .completionStage(() -> request.sendJsonObject(payload)
		                                        .toCompletionStage())
		          .map(response -> {
			          int status = response.statusCode();
			          // 2xx is delivered. Everything else, including a redirect we refuse to follow,
			          // is a failure the audit trail records by status alone.
			          return status >= 200 && status < 300
					          ? NotificationDispatch.Outcome.sent("HTTP " + status)
					          : NotificationDispatch.Outcome.failed("HTTP " + status);
		          })
		          .onFailure()
		          .recoverWithItem(failure -> {
			          log.warn("Notification {} webhook delivery failed for {}: {}", dispatch.notificationId(),
					          dispatch.recipientId(), failure.getMessage());
			          return NotificationDispatch.Outcome.failed(reason(failure));
		          });
	}

	/**
	 * Re-checks a destination the host supplied before anything is sent to it.
	 *
	 * @param url the configured webhook URL
	 * @return why the URL is unacceptable, or {@code null} when it may be called
	 */
	public static String reject(URI url)
	{
		String scheme = url.getScheme();
		if (scheme == null || !"https".equals(scheme.toLowerCase(Locale.ROOT)))
		{
			return "Webhook must use https";
		}
		if (url.getHost() == null || url.getHost()
		                                .isBlank())
		{
			return "Webhook has no host";
		}
		if (url.getUserInfo() != null)
		{
			return "Webhook must not carry credentials";
		}
		return null;
	}

	private WebClient client()
	{
		WebClient existing = client.get();
		if (existing != null)
		{
			return existing;
		}
		WebClient created = WebClient.create(VertXPreStartup.getVertx(),
				new WebClientOptions().setFollowRedirects(false)
				                      .setMaxRedirects(0)
				                      .setKeepAlive(true)
				                      .setUserAgent("ActivityMaster-Notifications"));
		if (client.compareAndSet(null, created))
		{
			return created;
		}
		created.close();
		return client.get();
	}

	private static long timeout()
	{
		try
		{
			return Math.max(1_000L,
					Long.parseLong(Environment.getSystemPropertyOrEnvironment(TIMEOUT_MILLIS, "10000")));
		}
		catch (NumberFormatException e)
		{
			return 10_000L;
		}
	}

	private static String reason(Throwable failure)
	{
		String message = failure.getMessage();
		return failure.getClass()
		              .getSimpleName() + (message == null ? "" : ": " + message);
	}
}
