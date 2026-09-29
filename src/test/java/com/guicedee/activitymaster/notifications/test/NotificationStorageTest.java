package com.guicedee.activitymaster.notifications.test;

import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import com.guicedee.activitymaster.fsdm.client.services.IEnterpriseService;
import com.guicedee.activitymaster.fsdm.client.services.IInvolvedPartyService;
import com.guicedee.activitymaster.fsdm.client.services.SessionUtils;
import com.guicedee.activitymaster.fsdm.client.services.administration.ActivityMasterConfiguration;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.party.IInvolvedParty;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;
import com.guicedee.activitymaster.mail.services.IMailTransportService;
import com.guicedee.activitymaster.notifications.INotificationService;
import com.guicedee.activitymaster.notifications.NotificationIdentity;
import com.guicedee.activitymaster.notifications.NotificationModels.Channel;
import com.guicedee.activitymaster.notifications.NotificationModels.Counts;
import com.guicedee.activitymaster.notifications.NotificationModels.Delivery;
import com.guicedee.activitymaster.notifications.NotificationModels.DeliveryResult;
import com.guicedee.activitymaster.notifications.NotificationModels.Notification;
import com.guicedee.activitymaster.notifications.NotificationModels.Page;
import com.guicedee.activitymaster.notifications.NotificationModels.Publish;
import com.guicedee.activitymaster.notifications.NotificationModels.Published;
import com.guicedee.activitymaster.notifications.NotificationModels.Severity;
import com.guicedee.activitymaster.notifications.NotificationModels.State;
import com.guicedee.activitymaster.notifications.NotificationService;
import com.guicedee.activitymaster.notifications.NotificationSystem;
import com.guicedee.client.IGuiceContext;
import com.guicedee.client.utils.Pair;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple4;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real FSDM path against a containerised PostgreSQL: publishing under a behaviour
 * grant, recipient-scoped reads, append-only state, and the delivery audit trail.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationStorageTest
{
	private static final String ENTERPRISE = "NotificationTest";

	private INotificationService service;
	private NotificationScopedFixture fixture;
	private UUID enterpriseId;
	private UUID systemId;
	private UUID publisherId;
	private UUID recipientId;
	private UUID otherRecipientId;
	private UUID outsiderId;
	private UUID pagingRecipientId;
	private UUID token;

	@BeforeAll
	void setup() throws Exception
	{
		var database = PostgreSQLTestDBModule.DATABASE;
		System.setProperty("ENVIRONMENT", "test");
		System.setProperty("FSDM_DBSERVER", "127.0.0.1");
		System.setProperty("FSDM_DBPORT", database.getFirstMappedPort()
		                                          .toString());
		System.setProperty("FSDM_DBNAME", database.getDatabaseName());
		System.setProperty("FSDM_USER", database.getUsername());
		System.setProperty("FSDM_PASSWORD", database.getPassword());
		System.setProperty("FSDM_SSL_MODE", "disable");
		ActivityMasterConfiguration.get()
		                           .setApplicationEnterpriseName(ENTERPRISE);
		IGuiceContext.instance();

		Mutiny.SessionFactory factory =
				IGuiceContext.get(Key.get(Mutiny.SessionFactory.class, Names.named("ActivityMaster-Test")));
		service = IGuiceContext.get(INotificationService.class);
		IEnterpriseService<?> enterprises = IGuiceContext.get(IEnterpriseService.class);
		factory.withStatelessSession(
				       session -> enterprises.startNewEnterprise(session, ENTERPRISE, "admin", "adminadmin!@"))
		       .await()
		       .atMost(Duration.ofMinutes(5));
		factory.withStatelessTransaction(session -> enterprises.getEnterprise(session, ENTERPRISE)
		                                                       .chain(e -> enterprises.loadUpdates(session, e)))
		       .await()
		       .atMost(Duration.ofMinutes(5));

		run(c -> {
			enterpriseId = c.getItem2()
			                .getId();
			systemId = c.getItem3()
			            .getId();
			token = c.getItem4()[0];
			IInvolvedPartyService<?> parties = IGuiceContext.get(IInvolvedPartyService.class);
			return parties.createIdentificationType(c.getItem1(), c.getItem3(), "NotificationTestId", "Test party",
					              token)
			              .chain(() -> party(parties, c))
			              .chain(publisher -> {
				              publisherId = publisher.getId();
				              return party(parties, c);
			              })
			              .chain(recipient -> {
				              recipientId = recipient.getId();
				              return party(parties, c);
			              })
			              .chain(other -> {
				              otherRecipientId = other.getId();
				              return party(parties, c);
			              })
			              .chain(outsider -> {
				              outsiderId = outsider.getId();
				              return party(parties, c);
			              })
			              // Paging and ordering need their own inbox, so filling it cannot disturb the
			              // tests that assert an untouched party sees nothing.
			              .invoke(paging -> pagingRecipientId = paging.getId())
			              .replaceWithVoid();
		});

		fixture = new NotificationScopedFixture(database, systemId, enterpriseId, token);
		fixture.install();
		fixture.grant(publisherId, INotificationService.PUBLISH_BEHAVIOR);
		fixture.grant(publisherId, INotificationService.AUDIT_BEHAVIOR);
	}

	private static Uni<IInvolvedParty<?, ?>> party(
			IInvolvedPartyService<?> parties,
			Tuple4<Mutiny.StatelessSession, IEnterprise<?, ?>, ISystems<?, ?>, UUID[]> c)
	{
		return parties.create(c.getItem1(), c.getItem3(),
				new Pair<>("NotificationTestId", UUID.randomUUID()
				                                     .toString()), true, c.getItem4()[0]);
	}

	private <T> T run(Function<Tuple4<Mutiny.StatelessSession, IEnterprise<?, ?>, ISystems<?, ?>, UUID[]>, Uni<T>> work)
	{
		return SessionUtils.withActivityMaster(ENTERPRISE, NotificationSystem.NAME, work)
		                   .await()
		                   .atMost(Duration.ofMinutes(2));
	}

	private NotificationIdentity identity(UUID party)
	{
		return new NotificationIdentity(party, enterpriseId,
				new ActivityScope.Context(ActivityScope.Realm.WORK, enterpriseId), token);
	}

	private Published publish(List<UUID> recipients)
	{
		return run(c -> service.publish(c.getItem1(), c.getItem3(), identity(publisherId),
				new Publish("billing", Severity.WARNING, "Invoice overdue", "Your invoice is overdue.",
						"{\"invoice\":\"INV-1\"}", recipients, List.of())));
	}

	@Test
	@DisplayName("Publishing needs the grant, and only recipients can see the result")
	void publishesToRecipientsOnly()
	{
		Published published = publish(List.of(recipientId, otherRecipientId));
		assertEquals(2, published.recipients()
		                         .size());

		Notification seen = run(c -> service.find(c.getItem1(), c.getItem3(), identity(recipientId), published.id()));
		assertEquals("billing", seen.category());
		assertEquals(Severity.WARNING, seen.severity());
		assertEquals("Invoice overdue", seen.subject());
		assertEquals("Your invoice is overdue.", seen.body());
		assertEquals("{\"invoice\":\"INV-1\"}", seen.data());
		assertEquals(publisherId, seen.publisherId());
		assertEquals(State.UNREAD, seen.state());
		assertNotNull(seen.createdAt());

		// A party who was not addressed cannot see it, and is told nothing beyond "not found".
		assertThrows(NotFoundException.class,
				() -> run(c -> service.find(c.getItem1(), c.getItem3(), identity(outsiderId), published.id())));
		assertTrue(run(c -> service.list(c.getItem1(), c.getItem3(), identity(outsiderId), null, null, 0, 10)).items()
				.isEmpty());

		// A party with no publish grant cannot publish at all.
		assertThrows(SecurityException.class, () -> run(c -> service.publish(c.getItem1(), c.getItem3(),
				identity(recipientId), new Publish("billing", Severity.INFO, "Nope", "Nope", null,
						List.of(outsiderId), List.of()))));
	}

	@Test
	@DisplayName("List omits bodies, filters by state and category, and pages")
	void listsAndFilters()
	{
		Published first = publish(List.of(recipientId));
		run(c -> service.publish(c.getItem1(), c.getItem3(), identity(publisherId),
				new Publish("shipping", Severity.INFO, "Parcel sent", "On its way.", null, List.of(recipientId),
						List.of())));

		Page<Notification> all =
				run(c -> service.list(c.getItem1(), c.getItem3(), identity(recipientId), null, null, 0, 100));
		assertTrue(all.items()
		              .size() >= 2);
		assertNull(all.items()
		              .getFirst()
		              .body(), "list responses must not carry bodies");

		Page<Notification> shipping =
				run(c -> service.list(c.getItem1(), c.getItem3(), identity(recipientId), null, "shipping", 0, 100));
		assertTrue(shipping.items()
		                   .stream()
		                   .allMatch(item -> "shipping".equals(item.category())));

		Page<Notification> firstPage =
				run(c -> service.list(c.getItem1(), c.getItem3(), identity(recipientId), null, null, 0, 1));
		assertEquals(1, firstPage.items()
		                         .size());
		assertTrue(firstPage.hasMore());

		assertThrows(BadRequestException.class,
				() -> run(c -> service.list(c.getItem1(), c.getItem3(), identity(recipientId), null, null, 0, 101)));
		assertNotNull(first);
	}

	@Test
	@DisplayName("State is append-only, idempotent, and scoped to the recipient recording it")
	void recordsStatePerRecipient()
	{
		Published published = publish(List.of(recipientId, otherRecipientId));

		Notification read =
				run(c -> service.transition(c.getItem1(), c.getItem3(), identity(recipientId), published.id(),
						State.READ));
		assertEquals(State.READ, read.state());

		// Repeating the transition writes nothing and still reports the current state.
		assertEquals(State.READ,
				run(c -> service.transition(c.getItem1(), c.getItem3(), identity(recipientId), published.id(),
						State.READ)).state());

		// One recipient reading does not change what another sees.
		assertEquals(State.UNREAD,
				run(c -> service.find(c.getItem1(), c.getItem3(), identity(otherRecipientId), published.id()))
						.state());

		assertEquals(State.ACKNOWLEDGED,
				run(c -> service.transition(c.getItem1(), c.getItem3(), identity(recipientId), published.id(),
						State.ACKNOWLEDGED)).state());
		assertEquals(State.DISMISSED,
				run(c -> service.transition(c.getItem1(), c.getItem3(), identity(recipientId), published.id(),
						State.DISMISSED)).state());

		// Dismissed drops out of the default inbox view but is still directly readable.
		assertFalse(run(c -> service.list(c.getItem1(), c.getItem3(), identity(recipientId), null, null, 0, 100))
				.items()
				.stream()
				.anyMatch(item -> item.id()
				                      .equals(published.id())));
		assertEquals(State.DISMISSED,
				run(c -> service.find(c.getItem1(), c.getItem3(), identity(recipientId), published.id())).state());

		// UNREAD is the absence of a state, not something that can be recorded.
		assertThrows(BadRequestException.class,
				() -> run(c -> service.transition(c.getItem1(), c.getItem3(), identity(recipientId), published.id(),
						State.UNREAD)));
		// A non-recipient cannot change state on someone else's notification.
		assertThrows(NotFoundException.class,
				() -> run(c -> service.transition(c.getItem1(), c.getItem3(), identity(outsiderId), published.id(),
						State.READ)));
	}

	@Test
	@DisplayName("readAll clears only the caller's unread notifications")
	void readsEverythingUnread()
	{
		publish(List.of(otherRecipientId));
		publish(List.of(otherRecipientId));
		long before = run(c -> service.counts(c.getItem1(), c.getItem3(), identity(otherRecipientId))).unread();
		assertTrue(before >= 2);

		long changed = run(c -> service.readAll(c.getItem1(), c.getItem3(), identity(otherRecipientId), null));
		assertEquals(before, changed);
		assertEquals(0L, run(c -> service.counts(c.getItem1(), c.getItem3(), identity(otherRecipientId))).unread());
		assertEquals(0L, (long) run(c -> service.readAll(c.getItem1(), c.getItem3(), identity(otherRecipientId), null)));
	}

	@Test
	@DisplayName("Delivery attempts are recorded and readable under the audit grant")
	void recordsDeliveryAttempts()
	{
		Published published = publish(List.of(recipientId));
		Delivery sent = run(c -> service.recordDelivery(c.getItem1(), c.getItem3(), identity(publisherId),
				published.id(), recipientId, Channel.MAIL, DeliveryResult.SENT, "Accepted by SMTP"));
		assertEquals(Channel.MAIL, sent.channel());
		assertEquals(DeliveryResult.SENT, sent.result());

		run(c -> service.recordDelivery(c.getItem1(), c.getItem3(), identity(publisherId), published.id(),
				recipientId, Channel.WEBHOOK, DeliveryResult.FAILED, "HTTP 503"));

		Page<Delivery> attempts = run(c -> service.deliveries(c.getItem1(), c.getItem3(), identity(publisherId),
				published.id(), 0, 100));
		assertEquals(2, attempts.items()
		                        .size());
		assertTrue(attempts.items()
		                   .stream()
		                   .anyMatch(attempt -> attempt.channel() == Channel.WEBHOOK
				                   && attempt.result() == DeliveryResult.FAILED
				                   && "HTTP 503".equals(attempt.detail())));

		// A recipient is not an auditor: reading the delivery trail needs its own grant.
		assertThrows(SecurityException.class,
				() -> run(c -> service.deliveries(c.getItem1(), c.getItem3(), identity(recipientId), published.id(),
						0, 100)));
	}

	@Test
	@DisplayName("Malformed and oversized content is rejected before anything is written")
	void validatesContent()
	{
		assertThrows(BadRequestException.class, () -> run(c -> service.publish(c.getItem1(), c.getItem3(),
				identity(publisherId), new Publish(null, Severity.INFO, "s", "b", null, List.of(recipientId),
						List.of()))));
		assertThrows(BadRequestException.class, () -> run(c -> service.publish(c.getItem1(), c.getItem3(),
				identity(publisherId), new Publish("billing", Severity.INFO, "s", "bad\u0000body", null,
						List.of(recipientId), List.of()))));
		assertThrows(BadRequestException.class, () -> run(c -> service.publish(c.getItem1(), c.getItem3(),
				identity(publisherId), new Publish("billing", Severity.INFO, "s", "\uD800", null,
						List.of(recipientId), List.of()))));
		assertThrows(BadRequestException.class, () -> run(c -> service.publish(c.getItem1(), c.getItem3(),
				identity(publisherId), new Publish("billing", Severity.INFO, "s", "b", null, List.of(), List.of()))));
		assertThrows(BadRequestException.class, () -> run(c -> service.publish(c.getItem1(), c.getItem3(),
				identity(publisherId), new Publish("billing", Severity.INFO, "s", "x".repeat(70_000), null,
						List.of(recipientId), List.of()))));
		// An unknown party is dropped rather than failing the publish; with none left it is an error.
		assertThrows(BadRequestException.class, () -> run(c -> service.publish(c.getItem1(), c.getItem3(),
				identity(publisherId), new Publish("billing", Severity.INFO, "s", "b", null,
						List.of(UUID.randomUUID()), List.of()))));
	}

	@Test
	@DisplayName("Mail Master binds its CRTP service under wildcard, concrete and raw keys")
	void mailTransportResolvesUnderEveryKey()
	{
		// The mail channel injects the wildcard form. Binding only the raw key, as Mail Master
		// previously did, made this exact lookup fail at injector boot.
		IMailTransportService<?> wildcard = IGuiceContext.get(Key.get(new TypeLiteral<IMailTransportService<?>>()
		{
		}));
		assertNotNull(wildcard);

		Object raw = IGuiceContext.get(IMailTransportService.class);
		assertNotNull(raw);
		assertSame(wildcard, raw, "every key must resolve to the one singleton");
	}

	@Test
	@DisplayName("Lists and delivery trails come back newest first, and never exceed the page size")
	void resultsAreDescendingAndBounded() throws Exception
	{
		List<UUID> published = new java.util.ArrayList<>();
		for (int i = 0; i < 5; i++)
		{
			published.add(publish(List.of(pagingRecipientId)).id());
			// Distinct warehouse timestamps, so "newest first" is actually observable.
			Thread.sleep(10);
		}

		Page<Notification> page =
				run(c -> service.list(c.getItem1(), c.getItem3(), identity(pagingRecipientId), null, null, 0, 3));
		assertEquals(3, page.items()
		                    .size(), "a page must never exceed the requested limit");
		assertTrue(page.hasMore());
		assertDescending(page.items()
		                     .stream()
		                     .map(Notification::createdAt)
		                     .toList());
		// The newest publish is the first row.
		assertEquals(published.getLast(), page.items()
		                                      .getFirst()
		                                      .id());

		Page<Notification> tail =
				run(c -> service.list(c.getItem1(), c.getItem3(), identity(pagingRecipientId), null, null, 3, 3));
		assertDescending(tail.items()
		                     .stream()
		                     .map(Notification::createdAt)
		                     .toList());
		assertTrue(tail.items()
		               .getFirst()
		               .createdAt()
		               .compareTo(page.items()
		                              .getLast()
		                              .createdAt()) <= 0, "page 2 must continue below page 1");

		UUID audited = published.getFirst();
		for (Channel channel : new Channel[]{Channel.EVENT_BUS, Channel.WEBHOOK, Channel.MAIL})
		{
			run(c -> service.recordDelivery(c.getItem1(), c.getItem3(), identity(publisherId), audited,
					pagingRecipientId, channel, DeliveryResult.SENT, channel.name()));
			Thread.sleep(10);
		}
		Page<Delivery> attempts =
				run(c -> service.deliveries(c.getItem1(), c.getItem3(), identity(publisherId), audited, 0, 2));
		assertEquals(2, attempts.items()
		                        .size());
		assertTrue(attempts.hasMore());
		assertDescending(attempts.items()
		                         .stream()
		                         .map(Delivery::attemptedAt)
		                         .toList());
		assertEquals(Channel.MAIL, attempts.items()
		                                   .getFirst()
		                                   .channel(), "the newest attempt comes first");
	}

	@Test
	@DisplayName("Counts stay inside their ceiling and say so")
	void countsAreBounded()
	{
		publish(List.of(recipientId));
		Counts counts = run(c -> service.counts(c.getItem1(), c.getItem3(), identity(recipientId)));
		assertTrue(counts.unread() >= 1);
		assertTrue(counts.total() >= counts.unread());
		assertFalse(counts.capped(), "well under the ceiling, so the counts are exact");
		assertTrue(counts.total() <= NotificationService.COUNT_CEILING);
	}

	@Test
	@DisplayName("Paging bounds are enforced at both ends")
	void pagingBoundsAreEnforced()
	{
		assertThrows(BadRequestException.class, () -> run(c -> service.list(c.getItem1(), c.getItem3(),
				identity(recipientId), null, null, -1, 10)));
		assertThrows(BadRequestException.class, () -> run(c -> service.list(c.getItem1(), c.getItem3(),
				identity(recipientId), null, null, NotificationService.MAX_OFFSET + 1, 10)));
		assertThrows(BadRequestException.class, () -> run(c -> service.list(c.getItem1(), c.getItem3(),
				identity(recipientId), null, null, 0, 0)));
		assertThrows(BadRequestException.class, () -> run(c -> service.list(c.getItem1(), c.getItem3(),
				identity(recipientId), null, null, 0, NotificationService.MAX_LIMIT + 1)));
		assertThrows(BadRequestException.class, () -> run(c -> service.deliveries(c.getItem1(), c.getItem3(),
				identity(publisherId), UUID.randomUUID(), 0, NotificationService.MAX_LIMIT + 1)));
		// The scan window always covers every page a caller is allowed to ask for.
		assertTrue(NotificationService.LIST_SCAN_CEILING
				>= NotificationService.MAX_OFFSET + NotificationService.MAX_LIMIT);
	}

	private static void assertDescending(List<OffsetDateTime> times)
	{
		for (int i = 1; i < times.size(); i++)
		{
			assertTrue(times.get(i - 1)
			                .compareTo(times.get(i)) >= 0,
					"results must be newest first, but " + times.get(i - 1) + " preceded " + times.get(i));
		}
	}

	@Test
	@DisplayName("Revoking the installation stops publishing immediately")
	void revokedGrantsStopPublishing() throws Exception
	{
		UUID grant = fixture.grant(outsiderId, INotificationService.PUBLISH_BEHAVIOR);
		assertNotNull(run(c -> service.publish(c.getItem1(), c.getItem3(), identity(outsiderId),
				new Publish("billing", Severity.INFO, "s", "b", null, List.of(recipientId), List.of()))));
		fixture.disable(grant);
		assertThrows(SecurityException.class, () -> run(c -> service.publish(c.getItem1(), c.getItem3(),
				identity(outsiderId), new Publish("billing", Severity.INFO, "s", "b", null, List.of(recipientId),
						List.of()))));
	}
}
