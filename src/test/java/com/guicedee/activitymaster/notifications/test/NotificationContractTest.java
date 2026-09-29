package com.guicedee.activitymaster.notifications.test;

import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;
import com.guicedee.activitymaster.notifications.NotificationIdentity;
import com.guicedee.activitymaster.notifications.NotificationModels.Channel;
import com.guicedee.activitymaster.notifications.NotificationModels.DeliveryResult;
import com.guicedee.activitymaster.notifications.NotificationModels.Page;
import com.guicedee.activitymaster.notifications.NotificationModels.Publish;
import com.guicedee.activitymaster.notifications.NotificationModels.Published;
import com.guicedee.activitymaster.notifications.NotificationModels.Severity;
import com.guicedee.activitymaster.notifications.channels.NotificationDispatch;
import com.guicedee.activitymaster.notifications.channels.WebhookNotificationChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Contract-level checks that need no database: the identity invariant that keeps a caller inside
 * their own context, the defensive copying that stops a caller mutating a request after validation,
 * and the webhook destination rules that keep this module from becoming an SSRF surface.
 */
class NotificationContractTest
{
	private static final UUID PARTY = UUID.randomUUID();
	private static final UUID ENTERPRISE = UUID.randomUUID();
	private static final UUID TOKEN = UUID.randomUUID();

	@Test
	@DisplayName("A work identity must own the enterprise context, a personal one its own party")
	void identityOwnerMustMatchContext()
	{
		assertNotNull(new NotificationIdentity(PARTY, ENTERPRISE,
				new ActivityScope.Context(ActivityScope.Realm.WORK, ENTERPRISE), TOKEN));
		assertNotNull(new NotificationIdentity(PARTY, ENTERPRISE,
				new ActivityScope.Context(ActivityScope.Realm.PERSONAL, PARTY), TOKEN));

		// A work context owned by a party, or a personal context owned by someone else, would let a
		// caller act outside the scope the host verified.
		assertThrows(SecurityException.class, () -> new NotificationIdentity(PARTY, ENTERPRISE,
				new ActivityScope.Context(ActivityScope.Realm.WORK, PARTY), TOKEN));
		assertThrows(SecurityException.class, () -> new NotificationIdentity(PARTY, ENTERPRISE,
				new ActivityScope.Context(ActivityScope.Realm.SOCIAL, UUID.randomUUID()), TOKEN));
		assertThrows(NullPointerException.class, () -> new NotificationIdentity(PARTY, ENTERPRISE,
				new ActivityScope.Context(ActivityScope.Realm.PERSONAL, PARTY), null));
	}

	@Test
	@DisplayName("Requests and responses copy their lists defensively")
	void transportValuesAreImmutable()
	{
		List<UUID> recipients = new ArrayList<>(List.of(PARTY));
		List<Channel> channels = new ArrayList<>(List.of(Channel.MAIL));
		Publish request = new Publish("billing", Severity.WARNING, "Subject", "Body", null, recipients, channels);

		recipients.add(UUID.randomUUID());
		channels.add(Channel.WEBHOOK);
		assertEquals(1, request.recipients()
		                       .size());
		assertEquals(1, request.channels()
		                       .size());
		assertThrows(UnsupportedOperationException.class, () -> request.recipients()
		                                                               .add(PARTY));

		assertEquals(List.of(PARTY), new Published(UUID.randomUUID(), List.of(PARTY)).recipients());
		assertThrows(UnsupportedOperationException.class, () -> new Page<>(List.of("a"), 0, 10, false).items()
		                                                                                              .add("b"));
	}

	@Test
	@DisplayName("Null recipients and channels are tolerated as empty, not as a crash")
	void nullCollectionsBecomeEmpty()
	{
		Publish request = new Publish("billing", null, "Subject", "Body", null, null, null);
		assertEquals(List.of(), request.recipients());
		assertEquals(List.of(), request.channels());
		assertNull(request.severity());
	}

	@Test
	@DisplayName("Only https destinations with a host and no credentials are callable")
	void webhookDestinationsAreRestricted()
	{
		assertNull(WebhookNotificationChannel.reject(URI.create("https://hooks.example.com/inbox")));

		assertEquals("Webhook must use https",
				WebhookNotificationChannel.reject(URI.create("http://hooks.example.com/inbox")));
		assertEquals("Webhook must use https",
				WebhookNotificationChannel.reject(URI.create("file:///etc/passwd")));
		assertEquals("Webhook must use https",
				WebhookNotificationChannel.reject(URI.create("/relative/path")));
		assertEquals("Webhook has no host", WebhookNotificationChannel.reject(URI.create("https:///nohost")));
		assertEquals("Webhook must not carry credentials",
				WebhookNotificationChannel.reject(URI.create("https://user:secret@hooks.example.com/inbox")));
	}

	@Test
	@DisplayName("Outcome factories carry the result they name")
	void outcomeFactories()
	{
		assertEquals(DeliveryResult.SENT, NotificationDispatch.Outcome.sent("HTTP 200")
		                                                              .result());
		assertEquals(DeliveryResult.SKIPPED, NotificationDispatch.Outcome.skipped("none configured")
		                                                                 .result());
		assertEquals(DeliveryResult.FAILED, NotificationDispatch.Outcome.failed("timeout")
		                                                                .result());
	}

	@Test
	@DisplayName("A dispatch always names an enterprise, a notification and a recipient")
	void dispatchRequiresItsSubjects()
	{
		assertNotNull(new NotificationDispatch(ENTERPRISE, "Acme", UUID.randomUUID(), PARTY, "billing",
				Severity.INFO, "Subject", "Body", null, OffsetDateTime.now()));
		assertThrows(NullPointerException.class,
				() -> new NotificationDispatch(null, "Acme", UUID.randomUUID(), PARTY, "billing", Severity.INFO,
						"Subject", "Body", null, OffsetDateTime.now()));
		assertThrows(NullPointerException.class,
				() -> new NotificationDispatch(ENTERPRISE, "Acme", UUID.randomUUID(), null, "billing", Severity.INFO,
						"Subject", "Body", null, OffsetDateTime.now()));
	}
}
