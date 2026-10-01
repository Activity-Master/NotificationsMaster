package com.guicedee.activitymaster.notifications;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.guicedee.activitymaster.fsdm.client.services.IActiveFlagService;
import com.guicedee.activitymaster.fsdm.client.services.IResourceItemService;
import com.guicedee.activitymaster.fsdm.client.services.ISecurityTokenService;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.activeflag.IActiveFlag;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.security.ISecurityToken;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.db.abstraction.WarehouseSCDTable;
import com.guicedee.activitymaster.fsdm.db.entities.events.Event;
import com.guicedee.activitymaster.fsdm.db.entities.events.EventXClassification;
import com.guicedee.activitymaster.fsdm.db.entities.events.EventXEvent;
import com.guicedee.activitymaster.fsdm.db.entities.events.EventXEventType;
import com.guicedee.activitymaster.fsdm.db.entities.events.EventXInvolvedParty;
import com.guicedee.activitymaster.fsdm.db.entities.events.EventXResourceItem;
import com.guicedee.activitymaster.fsdm.db.entities.resourceitem.ResourceItem;
import com.guicedee.activitymaster.fsdm.db.entities.resourceitem.ResourceItemXClassification;
import com.guicedee.activitymaster.fsdm.db.entities.resourceitem.ResourceItemXResourceItemType;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;
import com.guicedee.activitymaster.fsdm.transactions.FsdmBehaviorAuthority;
import com.guicedee.activitymaster.fsdm.plugins.PluginModels;
import com.guicedee.activitymaster.fsdm.plugins.PluginService;
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
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.hibernate.reactive.mutiny.Mutiny;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.guicedee.activitymaster.notifications.NotificationTaxonomy.*;

/**
 * Private FSDM rows. Access is derived from the addressed recipient and the trusted caller.
 * <p>
 * A notification is an {@code Event} of type {@code Notification}, linked to its publisher and to
 * every recipient through classified {@code EventXInvolvedParty} rows, with its body held in a
 * private {@code ResourceItem}. State is append-only: a read, dismissal or acknowledgement is its
 * own {@code Event} linked to the notification through {@code EventXEvent} and to the actor through
 * {@code EventXInvolvedParty}, and the current state is the latest live one. Nothing is updated in
 * place, so the full history of who saw what and when survives.
 * <p>
 * Every read is constrained by a live recipient link for the calling party. A caller who is not a
 * recipient gets {@link NotFoundException}, never a 403, so the API does not confirm that a
 * notification they cannot see exists.
 *
 * <h2>Behaviour under load</h2>
 * Every statement this class issues is bounded, and every ordered result is newest first.
 * <ul>
 *   <li>Existence probes fetch one row; taxonomy lookups fetch two, which is still enough to detect
 *       an ambiguous definition without scanning the rest.</li>
 *   <li>Reads are driven from the recipient link, so the actor selects the working set instead of
 *       the enterprise's whole event graph being scanned and then filtered.</li>
 *   <li>The recipient's notifications are narrowed to the newest {@link #LIST_SCAN_CEILING} before
 *       any filter or sort. Paging is capped at offset 10,000 and limit 100, so that window always
 *       covers every reachable page.</li>
 *   <li>{@link #counts} stops at {@link #COUNT_CEILING} and reports {@code capped}, so a badge on a
 *       hot page can never turn into an unbounded aggregate.</li>
 *   <li>Per-row correlated subqueries are replaced by {@code LEFT JOIN LATERAL} pivots, so the
 *       marker, state and body values are each collected in one pass rather than one pass per
 *       field. The pivots also make duplicate links impossible to multiply out into duplicate rows.</li>
 *   <li>Classification, event-type and security lookups are resolved once per operation and reused.
 *       Publishing to 500 recipients resolves the recipient role once, not 500 times.</li>
 * </ul>
 * Shared FSDM domain indexes cover scoped, current recipient links and classification pivots,
 * avoiding retained history on those reads. Existing current parent/child indexes serve state
 * and delivery links; payloads remain primary-key lookups. These access paths are installed by
 * the ordered core schema updates, including {@code 25.forum-notification-query-indexes.sql}.
 */
public final class NotificationService implements INotificationService
{
	/** Highest accepted paging offset. */
	public static final int MAX_OFFSET = 10_000;

	/** Highest accepted page size. */
	public static final int MAX_LIMIT = 100;

	/**
	 * How many of a recipient's newest notifications a list may consider.
	 * <p>
	 * Sized to {@link #MAX_OFFSET} plus {@link #MAX_LIMIT} so that every page a caller is allowed to
	 * ask for is inside the window, while a recipient with a million notifications still cannot make
	 * the database sort a million rows.
	 */
	public static final int LIST_SCAN_CEILING = MAX_OFFSET + MAX_LIMIT;

	/** How many of a recipient's newest notifications {@link #counts} considers. */
	public static final int COUNT_CEILING = 1_000;

	private static final OffsetDateTime END = OffsetDateTime.parse("2999-12-31T23:59:59Z");

	/** Delivery detail is a classification link value, and relationship values are varchar(150). */
	private static final int MAX_DETAIL = 150;

	@Inject
	private IActiveFlagService<?> flags;

	@Inject
	private ISecurityTokenService<?> security;

	@Inject
	private Provider<IResourceItemService<?>> resources;

	private final FsdmBehaviorAuthority behaviors = new FsdmBehaviorAuthority();
    @Inject private PluginService plugins;

	private record Scope(UUID enterprise, UUID system, UUID actor, String context)
	{
	}

	/**
	 * Everything an operation resolves once and reuses.
	 * <p>
	 * Classification ids, event type ids and the caller's security rows do not change inside a single
	 * transaction, and resolving them per row is what turned a 500-recipient publish into thousands
	 * of identical round trips. The maps live only for the duration of one call, so there is no
	 * cross-request staleness to reason about when a definition is superseded.
	 */
	private static final class Ctx
	{
		private final Scope scope;
		private final ISystems<?, ?> system;
		private final NotificationIdentity identity;
		private final Map<String, UUID> roles = new HashMap<>();
		private final Map<String, UUID> eventTypes = new HashMap<>();
		private IActiveFlag<?, ?> flag;
		private Map<String, ISecurityToken<?, ?>> groups;
		private ISecurityToken<?, ?> credential;

		private Ctx(Scope scope, ISystems<?, ?> system, NotificationIdentity identity)
		{
			this.scope = scope;
			this.system = system;
			this.identity = identity;
		}
	}

	// ── Guards ───────────────────────────────────────────────────────────────────────────────────

	private static String live(String alias)
	{
		return alias + ".enterpriseid=:enterprise and " + alias + ".systemid=:system and "
				+ alias + ".effectivefromdate<=statement_timestamp() and "
				+ alias + ".effectivetodate>statement_timestamp() and exists(select 1 from dbo.activeflag f where "
				+ "f.activeflagid=" + alias + ".activeflagid and f.allowaccess=1)";
	}

	private static void transaction(Mutiny.StatelessSession session)
	{
		if (session == null || session.currentTransaction() == null)
		{
			throw new IllegalStateException("Notification writes require a caller-owned transaction");
		}
	}

	private static void require(boolean condition, String message)
	{
		if (!condition)
		{
			throw new BadRequestException(message);
		}
	}

	private static void page(int offset, int limit)
	{
		require(offset >= 0 && offset <= MAX_OFFSET && limit >= 1 && limit <= MAX_LIMIT,
				"Offset must be 0.." + MAX_OFFSET + " and limit 1.." + MAX_LIMIT);
	}

	/**
	 * Rejects blank, oversized and null-bearing text, and rejects broken surrogate pairs — an
	 * unpaired surrogate survives a JSON round trip but corrupts the row it is written to.
	 */
	private static String bounded(String text, int max, String field, boolean required)
	{
		if (text == null || text.isBlank())
		{
			require(!required, field + " is required");
			return null;
		}
		require(text.length() <= max, field + " exceeds " + max + " characters");
		for (int i = 0; i < text.length(); i++)
		{
			char current = text.charAt(i);
			require(current != 0, field + " contains a null character");
			if (Character.isHighSurrogate(current))
			{
				require(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(++i)),
						field + " contains an invalid surrogate");
			}
			else
			{
				require(!Character.isLowSurrogate(current), field + " contains an invalid surrogate");
			}
		}
		return text;
	}

	private Scope scope(ISystems<?, ?> system, NotificationIdentity identity)
	{
		if (identity == null || system == null || system.getEnterprise() == null
				|| !NotificationSystem.NAME.equals(system.getName())
				|| !identity.enterpriseId()
				            .equals(system.getEnterprise()
				                          .getId()))
		{
			throw new SecurityException("Notification system scope mismatch");
		}
		return new Scope(identity.enterpriseId(), system.getId(), identity.partyId(),
				identity.context()
				        .realm()
				        .name() + ":" + identity.context()
				                                .ownerId());
	}

	/**
	 * Establishes that the caller is a live party holding a live credential with read access to this
	 * system and to their own party row, and optionally that they hold a scoped behaviour grant.
	 * <p>
	 * Every probe here fetches a single row: these run on every request, and the question each asks
	 * is "does at least one row exist", never "how many".
	 */
	private Uni<Ctx> reader(Mutiny.StatelessSession session, ISystems<?, ?> system, NotificationIdentity identity,
	                        String behavior)
	{
		Scope s = scope(system, identity);
		if (session == null)
		{
			return Uni.createFrom()
			          .failure(new IllegalArgumentException("Session required"));
		}
		boolean work = identity.context()
		                       .realm() == ActivityScope.Realm.WORK;
		return (identity.plugin() == null ? Uni.createFrom().voidItem()
                : plugins.check(session, system, new PluginModels.Identity(identity.partyId(), identity.enterpriseId(),
                        identity.identityToken()), identity.plugin()))
                .chain(() -> session.createNativeQuery("""
				        select 1 from security.securitytoken t where t.securitytoken=:token
				        and t.enterpriseid=:enterprise and t.effectivefromdate<=statement_timestamp()
				        and t.effectivetodate>statement_timestamp()
				        and exists(select 1 from dbo.activeflag f where f.activeflagid=t.activeflagid and f.allowaccess=1)
				        """, Integer.class)
		              .setParameter("token", identity.identityToken()
		                                             .toString())
		              .setParameter("enterprise", s.enterprise())
		              .setMaxResults(1)
		              .getResultList())
		              .chain(rows -> {
			              if (rows.isEmpty())
			              {
				              return Uni.createFrom()
				                        .failure(new SecurityException("Identity token unavailable"));
			              }
			              return session.createNativeQuery("select 1 from party.involvedparty p "
					                             + "where p.involvedpartyid=:actor and p.enterpriseid=:enterprise "
					                             + "and p.effectivefromdate<=statement_timestamp() "
					                             + "and p.effectivetodate>statement_timestamp() "
					                             + "and exists(select 1 from dbo.activeflag f where "
					                             + "f.activeflagid=p.activeflagid and f.allowaccess=1)"
					                             + (work ? "" : " and exists(select 1 from party.involvedpartyorganic o "
					                             + "where o.involvedpartyorganicid=p.involvedpartyid "
					                             + "and o.enterpriseid=:enterprise "
					                             + "and o.effectivefromdate<=statement_timestamp() "
					                             + "and o.effectivetodate>statement_timestamp() "
					                             + "and exists(select 1 from dbo.activeflag f where "
					                             + "f.activeflagid=o.activeflagid and f.allowaccess=1))"), Integer.class)
			                            .setParameter("actor", s.actor())
			                            .setParameter("enterprise", s.enterprise())
			                            .setMaxResults(1)
			                            .getResultList();
		              })
		              .chain(rows -> rows.isEmpty()
				              ? Uni.createFrom()
				                   .failure(new SecurityException("Actor unavailable"))
				              : grants(session, system, identity, s, behavior));
	}

	private Uni<Ctx> grants(Mutiny.StatelessSession session, ISystems<?, ?> system, NotificationIdentity identity,
	                        Scope s, String behavior)
	{
		return security.getApplicableSecurityTokenIds(session, system, identity.tokens())
		               .chain(tokens -> {
			               if (tokens == null || tokens.isEmpty())
			               {
				               return Uni.createFrom()
				                         .failure(new SecurityException("Actor token denied"));
			               }
			               return session.createNativeQuery("select 1 from dbo.systemssecuritytoken g "
					                              + "where g.systemid=:system and g.enterpriseid=:enterprise "
					                              + "and g.securitytokenid in (:tokens) and g.readallowed=1 and "
					                              + live("g"), Integer.class)
			                             .setParameter("system", s.system())
			                             .setParameter("enterprise", s.enterprise())
			                             .setParameter("tokens", tokens)
			                             .setMaxResults(1)
			                             .getResultList()
			                             .chain(systemGrants -> systemGrants.isEmpty()
					                             ? Uni.createFrom()
					                                  .failure(new SecurityException("System access denied"))
					                             : session.createNativeQuery(
							                             "select 1 from party.involvedpartysecuritytoken g "
									                             + "where g.involvedpartyid=:actor "
									                             + "and g.securitytokenid in (:tokens) "
									                             + "and g.readallowed=1 and " + live("g"), Integer.class)
					                                      .setParameter("actor", s.actor())
					                                      .setParameter("enterprise", s.enterprise())
					                                      .setParameter("system", s.system())
					                                      .setParameter("tokens", tokens)
					                                      .setMaxResults(1)
					                                      .getResultList()
					                                      .chain(actorGrants -> actorGrants.isEmpty()
							                                      ? Uni.createFrom()
							                                           .failure(new SecurityException(
									                                           "Actor access denied"))
							                                      : behaviour(session, s, system, identity,
							                                      behavior)));
		               });
	}

	private Uni<Ctx> behaviour(Mutiny.StatelessSession session, Scope s, ISystems<?, ?> system,
	                           NotificationIdentity identity, String behavior)
	{
		Ctx ctx = new Ctx(s, system, identity);
		if (behavior == null)
		{
			return Uni.createFrom()
			          .item(ctx);
		}
		return behaviors.check(session, s.system(), s.enterprise(), new ActivityScope.Actor(s.actor(), true),
				                identity.context(), identity.identityToken(), PROVIDER, behavior)
		                .replaceWith(ctx);
	}

	/**
	 * A reader plus the security rows every insert needs, resolved once instead of per row.
	 */
	private Uni<Ctx> writer(Mutiny.StatelessSession session, ISystems<?, ?> system, NotificationIdentity identity,
	                        String behavior)
	{
		return reader(session, system, identity, behavior).chain(ctx -> flags
				.getActiveFlag(session, system.getEnterprise(), identity.tokens())
				.chain(flag -> {
					ctx.flag = flag;
					return security.resolveDefaultGroupFolderTokens(session, system, identity.tokens());
				})
				.chain(groups -> {
					ctx.groups = groups;
					return security.getSecurityToken(session, identity.identityToken(), system, identity.tokens())
					               .onItem()
					               .ifNull()
					               .failWith(() -> new SecurityException("Notification credential unavailable"));
				})
				.map(credential -> {
					ctx.credential = credential;
					return ctx;
				}));
	}

	// ── Taxonomy resolution, memoised per operation ──────────────────────────────────────────────

	private Uni<UUID> eventType(Mutiny.StatelessSession session, Ctx ctx, String name)
	{
		UUID cached = ctx.eventTypes.get(name);
		if (cached != null)
		{
			return Uni.createFrom()
			          .item(cached);
		}
		return session.createNativeQuery("select eventtypeid from event.eventtype where enterpriseid=:enterprise "
				              + "and systemid=:system and eventtypename=:name "
				              + "and effectivefromdate<=statement_timestamp() and effectivetodate>statement_timestamp() "
				              + "and exists(select 1 from dbo.activeflag f where f.activeflagid=eventtype.activeflagid "
				              + "and f.allowaccess=1)", UUID.class)
		              .setParameter("enterprise", ctx.scope.enterprise())
		              .setParameter("system", ctx.scope.system())
		              .setParameter("name", name)
		              // Two rows is enough to tell "exactly one" from "ambiguous" without reading more.
		              .setMaxResults(2)
		              .getResultList()
		              .chain(rows -> rows.size() == 1
				              ? Uni.createFrom()
				                   .item(rows.getFirst())
				              : Uni.createFrom()
				                   .<UUID>failure(new IllegalStateException(
						                   "Missing or ambiguous notification event type: " + name)))
		              .invoke(id -> ctx.eventTypes.put(name, id));
	}

	private Uni<UUID> role(Mutiny.StatelessSession session, Ctx ctx, String name, String concept)
	{
		String key = concept + '/' + name;
		UUID cached = ctx.roles.get(key);
		if (cached != null)
		{
			return Uni.createFrom()
			          .item(cached);
		}
		return session.createNativeQuery("select c.classificationid from classification.classification c "
				              + "join classification.classificationdataconcept d "
				              + "on d.classificationdataconceptid=c.classificationdataconceptid "
				              + "where c.classificationname=:name and d.classificationdataconceptname=:concept "
				              + "and " + live("c") + " and d.enterpriseid=:enterprise "
				              + "and d.effectivefromdate<=statement_timestamp() "
				              + "and d.effectivetodate>statement_timestamp()", UUID.class)
		              .setParameter("enterprise", ctx.scope.enterprise())
		              .setParameter("system", ctx.scope.system())
		              .setParameter("name", name)
		              .setParameter("concept", concept)
		              .setMaxResults(2)
		              .getResultList()
		              .chain(rows -> rows.size() == 1
				              ? Uni.createFrom()
				                   .item(rows.getFirst())
				              : Uni.createFrom()
				                   .<UUID>failure(new IllegalStateException(
						                   "Missing or ambiguous notification role: " + name)))
		              .invoke(id -> ctx.roles.put(key, id));
	}

	private Uni<UUID> resourceType(Mutiny.StatelessSession session, Ctx ctx, String name)
	{
		return session.createNativeQuery("select resourceitemtypeid from resource.resourceitemtype "
				              + "where enterpriseid=:enterprise and systemid=:system and resourceitemtypename=:name "
				              + "and effectivefromdate<=statement_timestamp() and effectivetodate>statement_timestamp() "
				              + "and exists(select 1 from dbo.activeflag f where "
				              + "f.activeflagid=resourceitemtype.activeflagid and f.allowaccess=1)", UUID.class)
		              .setParameter("enterprise", ctx.scope.enterprise())
		              .setParameter("system", ctx.scope.system())
		              .setParameter("name", name)
		              .setMaxResults(2)
		              .getResultList()
		              .chain(rows -> rows.size() == 1
				              ? Uni.createFrom()
				                   .item(rows.getFirst())
				              : Uni.createFrom()
				                   .<UUID>failure(new IllegalStateException(
						                   "Missing or ambiguous notification resource type: " + name)));
	}

	// ── Writes ───────────────────────────────────────────────────────────────────────────────────

	private Uni<Void> insert(Mutiny.StatelessSession session, Ctx ctx, String table, String idColumn, UUID id,
	                         Map<String, Object> values)
	{
		Map<String, Object> fields = new LinkedHashMap<>(values);
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		fields.put(idColumn, id);
		fields.put("enterpriseid", ctx.scope.enterprise());
		fields.put("systemid", ctx.scope.system());
		fields.put("originalsourcesystemid", ctx.scope.system());
		fields.put("originalsourcesystemuniqueid", new UUID(0, 0));
		fields.put("activeflagid", ctx.flag.getId());
		fields.put("effectivefromdate", now);
		fields.put("effectivetodate", END);
		fields.put("warehousecreatedtimestamp", now);
		fields.put("warehouselastupdatedtimestamp", now);
		fields.put("warehousefromdate", now.toLocalDate());
		var query = session.createNativeQuery("insert into " + table + " (" + String.join(",", fields.keySet())
				+ ") values (" + fields.keySet()
				                       .stream()
				                       .map(key -> ":" + key)
				                       .collect(Collectors.joining(",")) + ")");
		fields.forEach(query::setParameter);
		WarehouseSCDTable<?, ?, ?, ?> row = securityRow(table, id);
		row.setEnterpriseID(ctx.system.getEnterprise())
		   .setSystemID(ctx.system)
		   .setActiveFlagID(ctx.flag);
		// Restricted matrix plus an explicit grant to the acting credential: notifications are private
		// records, never readable through a default Everyone grant. The flag, group tokens and
		// credential were all resolved once by writer(), so this is three statements, not six.
		return query.executeUpdate()
		            .chain(() -> row.createScopeRestrictedSecurity(session, ctx.system,
				            ctx.system.getEnterprise(), ctx.flag, ctx.groups, null, ctx.identity.tokens()))
		            .chain(() -> row.createSecurityGrant(session, ctx.system, ctx.system.getEnterprise(), ctx.flag,
				            ctx.credential, true, true, false, true, ctx.identity.tokens()))
		            .replaceWithVoid();
	}

	private static WarehouseSCDTable<?, ?, ?, ?> securityRow(String table, UUID id)
	{
		return switch (table)
		{
			case "event.event" -> new Event().setId(id);
			case "event.eventxeventtype" -> new EventXEventType().setId(id);
			case "event.eventxinvolvedparty" -> new EventXInvolvedParty().setId(id);
			case "event.eventxclassification" -> new EventXClassification().setId(id);
			case "event.eventxevent" -> new EventXEvent().setId(id);
			case "event.eventxresourceitem" -> new EventXResourceItem().setId(id);
			case "resource.resourceitem" -> new ResourceItem().setId(id);
			case "resource.resourceitemxclassification" -> new ResourceItemXClassification().setId(id);
			case "resource.resourceitemxresourceitemtype" -> new ResourceItemXResourceItemType().setId(id);
			default -> throw new IllegalArgumentException("Unsupported notification FSDM table: " + table);
		};
	}

	private Uni<Void> link(Mutiny.StatelessSession session, Ctx ctx, String table, String idColumn, String roleName,
	                       String concept, Map<String, Object> values)
	{
		return role(session, ctx, roleName, concept).chain(classification -> {
			Map<String, Object> fields = new HashMap<>(values);
			fields.put("classificationid", classification);
			fields.putIfAbsent("value", "1");
			return insert(session, ctx, table, idColumn, UUID.randomUUID(), fields);
		});
	}

	private Uni<Void> marker(Mutiny.StatelessSession session, Ctx ctx, UUID event, String roleName, String value)
	{
		return link(session, ctx, "event.eventxclassification", "eventxclassificationid", roleName,
				"EventXClassification", Map.of("eventid", event, "value", value));
	}

	private Uni<Void> party(Mutiny.StatelessSession session, Ctx ctx, UUID event, String roleName, UUID partyId)
	{
		return link(session, ctx, "event.eventxinvolvedparty", "eventxinvolvedpartyid", roleName,
				"EventXInvolvedParty", Map.of("eventid", event, "involvedpartyid", partyId));
	}

	private Uni<Void> child(Mutiny.StatelessSession session, Ctx ctx, UUID parent, UUID childEvent, String roleName)
	{
		return link(session, ctx, "event.eventxevent", "eventxeventid", roleName, "EventXEvent",
				Map.of("parenteventid", parent, "childeventid", childEvent));
	}

	private Uni<Void> newEvent(Mutiny.StatelessSession session, Ctx ctx, UUID id, String typeName, String typeRole)
	{
		return eventType(session, ctx, typeName).chain(typeId -> insert(session, ctx, "event.event", "eventid", id,
				                                                Map.of("dayid", 0, "hourid", 0, "minuteid", 0))
		                                                        .chain(() -> link(session, ctx,
				                                                        "event.eventxeventtype", "eventxeventtypeid",
				                                                        typeRole, "EventXEventType",
				                                                        Map.of("eventid", id, "eventtypeid",
						                                                        typeId))));
	}

	// ── Publish ──────────────────────────────────────────────────────────────────────────────────

	@Override
	public Uni<Published> publish(Mutiny.StatelessSession session, ISystems<?, ?> system,
	                              NotificationIdentity identity, Publish request)
	{
		transaction(session);
		require(request != null, "Request required");
		String category = bounded(request.category(), NotificationModels.MAX_CATEGORY, "category", true);
		String subject = bounded(request.subject(), NotificationModels.MAX_SUBJECT, "subject", true);
		String body = bounded(request.body(), NotificationModels.MAX_BODY, "body", true);
		String data = bounded(request.data(), NotificationModels.MAX_DATA, "data", false);
		Severity severity = request.severity() == null ? Severity.INFO : request.severity();
		Set<UUID> recipients = new LinkedHashSet<>(request.recipients());
		recipients.remove(null);
		require(!recipients.isEmpty(), "At least one recipient is required");
		require(recipients.size() <= NotificationModels.MAX_RECIPIENTS,
				"At most " + NotificationModels.MAX_RECIPIENTS + " recipients per notification");

		return writer(session, system, identity, PUBLISH_BEHAVIOR).chain(
				ctx -> livingParties(session, ctx, recipients).chain(living -> {
					require(!living.isEmpty(), "No live recipient in this enterprise");
					UUID id = UUID.randomUUID();
					UUID bodyId = UUID.randomUUID();
					Uni<Void> chain = newEvent(session, ctx, id, NOTIFICATION_EVENT, NOTIFICATION_TYPE_ROLE)
							.chain(() -> party(session, ctx, id, PUBLISHER_ROLE, ctx.scope.actor()))
							.chain(() -> marker(session, ctx, id, CATEGORY_ROLE, category))
							.chain(() -> marker(session, ctx, id, SEVERITY_ROLE, severity.name()))
							.chain(() -> marker(session, ctx, id, SUBJECT_ROLE, subject))
							.chain(() -> marker(session, ctx, id, CONTEXT_ROLE, ctx.scope.context()))
							.chain(() -> body(session, ctx, id, bodyId, body, data));
					// The recipient role resolves on the first link and is served from the operation
					// cache for the remaining recipients.
					for (UUID recipient : living)
					{
						chain = chain.chain(() -> party(session, ctx, id, RECIPIENT_ROLE, recipient));
					}
					return chain.replaceWith(() -> new Published(id, List.copyOf(living)));
				}));
	}

	/**
	 * Writes the body and structured payload as the <em>data</em> of a private resource item.
	 * <p>
	 * A relationship value is {@code varchar(150)} and is meant for short discriminators, so a body
	 * of up to 64k belongs in resource item data, not in a classification link. Body and payload
	 * travel together as one JSON document, which keeps a read to a single data fetch.
	 */
	private Uni<Void> body(Mutiny.StatelessSession session, Ctx ctx, UUID notification, UUID bodyId, String body,
	                       String data)
	{
		JsonObject document = new JsonObject().put("body", body);
		if (data != null)
		{
			document.put("data", data);
		}
		byte[] bytes = document.encode()
		                       .getBytes(StandardCharsets.UTF_8);
		return resources.get()
		                .create(session, BODY_RESOURCE, bodyId, BODY_RESOURCE, bytes, ctx.system,
				                ctx.identity.tokens())
		                .chain(() -> link(session, ctx, "event.eventxresourceitem", "eventxresourceitemid", BODY_ROLE,
				                "EventXResourceItem", Map.of("eventid", notification, "resourceitemid", bodyId)));
	}

	/**
	 * Reads the body document back. Returns an empty object when the item is absent or unreadable,
	 * so a notification whose body row was retired still lists rather than failing the whole read.
	 */
	private Uni<JsonObject> bodyDocument(Mutiny.StatelessSession session, Ctx ctx, UUID bodyId)
	{
		if (bodyId == null)
		{
			return Uni.createFrom()
			          .item(new JsonObject());
		}
		return resources.get()
		                .findByUUID(session, bodyId)
		                .chain(item -> item == null
				                ? Uni.createFrom()
				                     .<byte[]>nullItem()
				                : item.getData(session, ctx.identity.tokens()))
		                .map(bytes -> bytes == null || bytes.length == 0
				                ? new JsonObject()
				                : new JsonObject(new String(bytes, StandardCharsets.UTF_8)))
		                .onFailure()
		                .recoverWithItem(new JsonObject());
	}

	/**
	 * Filters the requested recipients down to parties that are live in this enterprise. Silently
	 * dropping the rest keeps a publish from failing wholesale on one stale id, and the response
	 * reports exactly who was addressed.
	 */
	private Uni<List<UUID>> livingParties(Mutiny.StatelessSession session, Ctx ctx, Set<UUID> requested)
	{
		return session.createNativeQuery("select p.involvedpartyid from party.involvedparty p "
				              + "where p.involvedpartyid in (:ids) and p.enterpriseid=:enterprise "
				              + "and p.effectivefromdate<=statement_timestamp() "
				              + "and p.effectivetodate>statement_timestamp() "
				              + "and exists(select 1 from dbo.activeflag f where f.activeflagid=p.activeflagid "
				              + "and f.allowaccess=1)", UUID.class)
		              .setParameter("ids", List.copyOf(requested))
		              .setParameter("enterprise", ctx.scope.enterprise())
		              .setMaxResults(NotificationModels.MAX_RECIPIENTS)
		              .getResultList()
		              .map(found -> requested.stream()
		                                     .filter(found::contains)
		                                     .toList());
	}

	// ── Read shapes ──────────────────────────────────────────────────────────────────────────────

	/**
	 * The recipient's newest notifications, bounded and newest first.
	 * <p>
	 * Driven from {@code event.eventxinvolvedparty} by {@code involvedpartyid} so the actor selects
	 * the working set. {@code distinct} makes a duplicated recipient or type link impossible to
	 * multiply into duplicate notifications, and the inner {@code limit} means no caller can make the
	 * database sort more than {@link #LIST_SCAN_CEILING} rows however many notifications they hold.
	 */
	private static String recipientWindow(int ceiling)
	{
		return """
				(select distinct r.eventid as eventid, e.warehousecreatedtimestamp as created
				 from event.eventxinvolvedparty r
				 join classification.classification rr on rr.classificationid=r.classificationid
				 join event.event e on e.eventid=r.eventid
				 join event.eventxeventtype et on et.eventid=e.eventid
				 join event.eventtype t on t.eventtypeid=et.eventtypeid
				 join classification.classification tr on tr.classificationid=et.classificationid
				 where r.involvedpartyid=:actor
				"""
				+ "   and " + live("r") + " and " + live("rr") + " and " + live("e") + " and " + live("et")
				+ " and " + live("t") + " and " + live("tr")
				+ "   and rr.classificationname='" + RECIPIENT_ROLE + "'"
				+ "   and tr.classificationname='" + NOTIFICATION_TYPE_ROLE + "'"
				+ "   and t.eventtypename='" + NOTIFICATION_EVENT + "'"
				+ " order by e.warehousecreatedtimestamp desc, r.eventid desc"
				+ " limit " + ceiling + ") n";
	}

	/**
	 * One pass over a notification's classification links, pivoted into the three header fields.
	 * Three correlated subqueries became one lateral.
	 */
	private static String markerPivot()
	{
		return " left join lateral (select"
				+ "   max(x.value) filter (where c.classificationname='" + CATEGORY_ROLE + "') as category,"
				+ "   max(x.value) filter (where c.classificationname='" + SEVERITY_ROLE + "') as severity,"
				+ "   max(x.value) filter (where c.classificationname='" + SUBJECT_ROLE + "') as subject"
				+ " from event.eventxclassification x"
				+ " join classification.classification c on c.classificationid=x.classificationid"
				+ " where x.eventid=n.eventid and " + live("x") + " and " + live("c")
				+ " and c.classificationname in ('" + CATEGORY_ROLE + "','" + SEVERITY_ROLE + "','"
				+ SUBJECT_ROLE + "')) m on true";
	}

	/** The caller's latest live state on the notification, or {@code null} when unread. */
	private static String statePivot()
	{
		return " left join lateral (select sc.value as state"
				+ " from event.eventxevent xe"
				+ " join classification.classification xr on xr.classificationid=xe.classificationid"
				+ " join event.event se on se.eventid=xe.childeventid"
				+ " join event.eventxclassification sc on sc.eventid=se.eventid"
				+ " join classification.classification scr on scr.classificationid=sc.classificationid"
				+ " join event.eventxinvolvedparty sa on sa.eventid=se.eventid"
				+ " join classification.classification sar on sar.classificationid=sa.classificationid"
				+ " where xe.parenteventid=n.eventid and sa.involvedpartyid=:actor"
				+ " and " + live("xe") + " and " + live("xr") + " and " + live("se") + " and " + live("sc")
				+ " and " + live("scr") + " and " + live("sa") + " and " + live("sar")
				+ " and xr.classificationname='" + STATE_OF_ROLE + "'"
				+ " and scr.classificationname='" + STATE_ROLE + "'"
				+ " and sar.classificationname='" + STATE_ACTOR_ROLE + "'"
				+ " order by se.warehousecreatedtimestamp desc, se.eventid desc limit 1) st on true";
	}

	private static String publisherPivot()
	{
		return " left join lateral (select pp.involvedpartyid as publisher"
				+ " from event.eventxinvolvedparty pp"
				+ " join classification.classification pr on pr.classificationid=pp.classificationid"
				+ " where pp.eventid=n.eventid and " + live("pp") + " and " + live("pr")
				+ " and pr.classificationname='" + PUBLISHER_ROLE + "' limit 1) pub on true";
	}

	/** The body resource item id. Its contents are fetched separately, as resource item data. */
	private static String bodyPivot()
	{
		return " left join lateral (select xri.resourceitemid as bodyid"
				+ " from event.eventxresourceitem xri"
				+ " join classification.classification xrr on xrr.classificationid=xri.classificationid"
				+ " where xri.eventid=n.eventid and " + live("xri") + " and " + live("xrr")
				+ " and xrr.classificationname='" + BODY_ROLE + "' limit 1) bd on true";
	}

	// ── Reads ────────────────────────────────────────────────────────────────────────────────────

	@Override
	public Uni<Notification> find(Mutiny.StatelessSession session, ISystems<?, ?> system,
	                              NotificationIdentity identity, UUID id)
	{
		require(id != null, "Notification id required");
		return reader(session, system, identity, null).chain(ctx -> findWith(session, ctx, id));
	}

	private Uni<Notification> findWith(Mutiny.StatelessSession session, Ctx ctx, UUID id)
	{
		String sql = "select n.eventid, m.category, m.severity, m.subject, bd.bodyid, pub.publisher,"
				+ " n.created, st.state from " + recipientWindow(LIST_SCAN_CEILING) + markerPivot() + statePivot()
				+ publisherPivot() + bodyPivot() + " where n.eventid=:id";
		return session.createNativeQuery(sql, Object[].class)
		              .setParameter("enterprise", ctx.scope.enterprise())
		              .setParameter("system", ctx.scope.system())
		              .setParameter("actor", ctx.scope.actor())
		              .setParameter("id", id)
		              .setMaxResults(1)
		              .getResultList()
		              .chain(rows -> {
			              if (rows.isEmpty())
			              {
				              return Uni.createFrom()
				                        .failure(new NotFoundException());
			              }
			              Object[] row = rows.getFirst();
			              // The body lives in resource item data, so it is a second fetch — and only on
			              // a single read. Lists never pay for it.
			              return bodyDocument(session, ctx, uuid(row[4])).map(document -> new Notification(
					              uuid(row[0]), text(row[1]), severity(text(row[2])), text(row[3]),
					              document.getString("body"), document.getString("data"), uuid(row[5]),
					              time(row[6]),
					              row[7] == null ? State.UNREAD : State.valueOf(text(row[7]).toUpperCase(Locale.ROOT))));
		              });
	}

	@Override
	public Uni<Page<Notification>> list(Mutiny.StatelessSession session, ISystems<?, ?> system,
	                                    NotificationIdentity identity, State state, String category, int offset,
	                                    int limit)
	{
		page(offset, limit);
		String filter = bounded(category, NotificationModels.MAX_CATEGORY, "category", false);
		return reader(session, system, identity, null).chain(ctx -> {
			StringBuilder sql = new StringBuilder("select n.eventid, m.category, m.severity, m.subject,"
					+ " cast(null as text), cast(null as text), pub.publisher, n.created, st.state from ")
					.append(recipientWindow(LIST_SCAN_CEILING))
					.append(markerPivot())
					.append(statePivot())
					.append(publisherPivot())
					.append(" where 1=1");
			if (filter != null)
			{
				sql.append(" and m.category=:category");
			}
			// UNREAD is the absence of a state row; the other filters compare the latest live state.
			// With no filter at all, dismissed notifications drop out of the default inbox view.
			if (state == State.UNREAD)
			{
				sql.append(" and st.state is null");
			}
			else if (state != null)
			{
				sql.append(" and st.state=:state");
			}
			else
			{
				sql.append(" and coalesce(st.state,'')<>'")
				   .append(State.DISMISSED.name())
				   .append("'");
			}
			sql.append(" order by n.created desc, n.eventid desc");

			var query = session.createNativeQuery(sql.toString(), Object[].class)
			                   .setParameter("enterprise", ctx.scope.enterprise())
			                   .setParameter("system", ctx.scope.system())
			                   .setParameter("actor", ctx.scope.actor());
			if (filter != null)
			{
				query.setParameter("category", filter);
			}
			if (state != null && state != State.UNREAD)
			{
				query.setParameter("state", state.name());
			}
			// One extra row answers hasMore without exposing an unfiltered count.
			return query.setFirstResult(offset)
			            .setMaxResults(limit + 1)
			            .getResultList()
			            .map(rows -> {
				            boolean hasMore = rows.size() > limit;
				            List<Notification> items = new ArrayList<>(Math.min(rows.size(), limit));
				            for (int i = 0; i < Math.min(rows.size(), limit); i++)
				            {
					            items.add(notification(rows.get(i), false));
				            }
				            return new Page<>(items, offset, limit, hasMore);
			            });
		});
	}

	@Override
	public Uni<Counts> counts(Mutiny.StatelessSession session, ISystems<?, ?> system, NotificationIdentity identity)
	{
		return reader(session, system, identity, null).chain(ctx -> session.createNativeQuery(
				                                                           "select count(*) filter (where q.state is null), "
						                                                           + "count(*) filter (where coalesce(q.state,'')<>'" + State.DISMISSED.name()
						                                                           + "'), count(*) from (select st.state as state from "
						                                                           + recipientWindow(COUNT_CEILING) + statePivot() + ") q", Object[].class)
		                                                                   .setParameter("enterprise",
				                                                                   ctx.scope.enterprise())
		                                                                   .setParameter("system", ctx.scope.system())
		                                                                   .setParameter("actor", ctx.scope.actor())
		                                                                   .setMaxResults(1)
		                                                                   .getSingleResult()
		                                                                   .map(row -> new Counts(number(row[0]),
				                                                                   number(row[1]),
				                                                                   number(row[2]) >= COUNT_CEILING)));
	}

	// ── State transitions ────────────────────────────────────────────────────────────────────────

	@Override
	public Uni<Notification> transition(Mutiny.StatelessSession session, ISystems<?, ?> system,
	                                    NotificationIdentity identity, UUID id, State state)
	{
		transaction(session);
		require(id != null, "Notification id required");
		require(state != null && state != State.UNREAD, "State must be READ, DISMISSED or ACKNOWLEDGED");
		// One authorisation pass for the whole transition, not one per read and one per write.
		return writer(session, system, identity, null).chain(ctx -> findWith(session, ctx, id).chain(current -> {
			if (current.state() == state)
			{
				// Idempotent: the caller already holds this state, so no second state event is written.
				return Uni.createFrom()
				          .item(current);
			}
			return recordState(session, ctx, id, state).chain(() -> findWith(session, ctx, id));
		}));
	}

	private Uni<Void> recordState(Mutiny.StatelessSession session, Ctx ctx, UUID notification, State state)
	{
		UUID stateId = UUID.randomUUID();
		return newEvent(session, ctx, stateId, STATE_EVENT, STATE_TYPE_ROLE)
				.chain(() -> marker(session, ctx, stateId, STATE_ROLE, state.name()))
				.chain(() -> party(session, ctx, stateId, STATE_ACTOR_ROLE, ctx.scope.actor()))
				.chain(() -> child(session, ctx, notification, stateId, STATE_OF_ROLE));
	}

	@Override
	public Uni<Long> readAll(Mutiny.StatelessSession session, ISystems<?, ?> system, NotificationIdentity identity,
	                         String category)
	{
		transaction(session);
		String filter = bounded(category, NotificationModels.MAX_CATEGORY, "category", false);
		return writer(session, system, identity, null).chain(ctx -> {
			StringBuilder sql = new StringBuilder("select n.eventid from ").append(
					                                                               recipientWindow(LIST_SCAN_CEILING))
			                                                               .append(markerPivot())
			                                                               .append(statePivot())
			                                                               .append(" where st.state is null");
			if (filter != null)
			{
				sql.append(" and m.category=:category");
			}
			sql.append(" order by n.created desc, n.eventid desc");
			var query = session.createNativeQuery(sql.toString(), UUID.class)
			                   .setParameter("enterprise", ctx.scope.enterprise())
			                   .setParameter("system", ctx.scope.system())
			                   .setParameter("actor", ctx.scope.actor());
			if (filter != null)
			{
				query.setParameter("category", filter);
			}
			// Bounded so one call cannot write an unbounded number of rows in a single transaction;
			// the caller repeats until it returns zero.
			return query.setMaxResults(READ_ALL_BATCH)
			            .getResultList()
			            .chain(ids -> {
				            Uni<Long> chain = Uni.createFrom()
				                                 .item(0L);
				            for (UUID id : ids)
				            {
					            chain = chain.chain(
							            count -> recordState(session, ctx, id, State.READ).replaceWith(count + 1));
				            }
				            return chain;
			            });
		});
	}

	/** How many notifications one {@code readAll} call may transition. */
	public static final int READ_ALL_BATCH = 200;

	// ── Delivery attempts ────────────────────────────────────────────────────────────────────────

	@Override
	public Uni<Delivery> recordDelivery(Mutiny.StatelessSession session, ISystems<?, ?> system,
	                                    NotificationIdentity identity, UUID id, UUID recipientId, Channel channel,
	                                    DeliveryResult result, String detail)
	{
		transaction(session);
		require(id != null && recipientId != null && channel != null && result != null,
				"Notification, recipient, channel and result are required");
		String explanation = detail == null ? "" : bounded(detail, MAX_DETAIL, "detail", false);
		String recorded = explanation == null ? "" : explanation;
		return writer(session, system, identity, PUBLISH_BEHAVIOR).chain(ctx -> {
			UUID deliveryId = UUID.randomUUID();
			OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
			return newEvent(session, ctx, deliveryId, DELIVERY_EVENT, DELIVERY_TYPE_ROLE)
					.chain(() -> marker(session, ctx, deliveryId, DELIVERY_CHANNEL_ROLE, channel.name()))
					.chain(() -> marker(session, ctx, deliveryId, DELIVERY_RESULT_ROLE, result.name()))
					.chain(() -> marker(session, ctx, deliveryId, DELIVERY_DETAIL_ROLE, recorded))
					.chain(() -> party(session, ctx, deliveryId, DELIVERY_RECIPIENT_ROLE, recipientId))
					.chain(() -> child(session, ctx, id, deliveryId, DELIVERY_OF_ROLE))
					.replaceWith(() -> new Delivery(deliveryId, id, recipientId, channel, result, recorded, now));
		});
	}

	@Override
	public Uni<Page<Delivery>> deliveries(Mutiny.StatelessSession session, ISystems<?, ?> system,
	                                      NotificationIdentity identity, UUID id, int offset, int limit)
	{
		page(offset, limit);
		require(id != null, "Notification id required");
		// One lateral pivot rather than three joins to eventxclassification: as well as being one
		// pass, it makes a duplicated channel or result link impossible to multiply into extra rows.
		String sql = "select d.eventid, dr.involvedpartyid, f.channel, f.result, f.detail,"
				+ " d.warehousecreatedtimestamp"
				+ " from event.eventxevent xe"
				+ " join classification.classification xr on xr.classificationid=xe.classificationid"
				+ " join event.event d on d.eventid=xe.childeventid"
				+ " left join lateral (select"
				+ "   max(dc.value) filter (where dcr.classificationname='" + DELIVERY_CHANNEL_ROLE + "') as channel,"
				+ "   max(dc.value) filter (where dcr.classificationname='" + DELIVERY_RESULT_ROLE + "') as result,"
				+ "   max(dc.value) filter (where dcr.classificationname='" + DELIVERY_DETAIL_ROLE + "') as detail"
				+ " from event.eventxclassification dc"
				+ " join classification.classification dcr on dcr.classificationid=dc.classificationid"
				+ " where dc.eventid=d.eventid and " + live("dc") + " and " + live("dcr") + ") f on true"
				+ " left join lateral (select dp.involvedpartyid from event.eventxinvolvedparty dp"
				+ " join classification.classification dpr on dpr.classificationid=dp.classificationid"
				+ " where dp.eventid=d.eventid and " + live("dp") + " and " + live("dpr")
				+ " and dpr.classificationname='" + DELIVERY_RECIPIENT_ROLE + "' limit 1) dr on true"
				+ " where xe.parenteventid=:id and " + live("xe") + " and " + live("xr") + " and " + live("d")
				+ " and xr.classificationname='" + DELIVERY_OF_ROLE + "'"
				+ " order by d.warehousecreatedtimestamp desc, d.eventid desc";
		return reader(session, system, identity, AUDIT_BEHAVIOR).chain(ctx -> session.createNativeQuery(sql,
				                                                                     Object[].class)
		                                                                             .setParameter("enterprise",
				                                                                             ctx.scope.enterprise())
		                                                                             .setParameter("system",
				                                                                             ctx.scope.system())
		                                                                             .setParameter("id", id)
		                                                                             .setFirstResult(offset)
		                                                                             .setMaxResults(limit + 1)
		                                                                             .getResultList()
		                                                                             .map(rows -> {
			                                                                             boolean hasMore =
					                                                                             rows.size() > limit;
			                                                                             List<Delivery> items =
					                                                                             new ArrayList<>();
			                                                                             for (int i = 0;
			                                                                                  i < Math.min(rows.size(),
							                                                                          limit); i++)
			                                                                             {
				                                                                             items.add(delivery(id,
						                                                                             rows.get(i)));
			                                                                             }
			                                                                             return new Page<>(items,
					                                                                             offset, limit,
					                                                                             hasMore);
		                                                                             }));
	}

	// ── Row mapping ──────────────────────────────────────────────────────────────────────────────

	private static Notification notification(Object[] row, boolean withBody)
	{
		State state = row[8] == null ? State.UNREAD : State.valueOf(text(row[8]).toUpperCase(Locale.ROOT));
		return new Notification(uuid(row[0]), text(row[1]), severity(text(row[2])), text(row[3]),
				withBody ? text(row[4]) : null, withBody ? text(row[5]) : null, uuid(row[6]), time(row[7]), state);
	}

	private static Delivery delivery(UUID notification, Object[] row)
	{
		return new Delivery(uuid(row[0]), notification, uuid(row[1]),
				Channel.valueOf(text(row[2]).toUpperCase(Locale.ROOT)),
				DeliveryResult.valueOf(text(row[3]).toUpperCase(Locale.ROOT)), text(row[4]), time(row[5]));
	}

	private static Severity severity(String value)
	{
		if (value == null)
		{
			return Severity.INFO;
		}
		try
		{
			return Severity.valueOf(value.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e)
		{
			// A severity written by a newer release must not make an older one unable to read the row.
			return Severity.INFO;
		}
	}

	private static UUID uuid(Object value)
	{
		return value == null ? null : value instanceof UUID id ? id : UUID.fromString(value.toString());
	}

	private static String text(Object value)
	{
		return value == null ? null : value.toString();
	}

	private static long number(Object value)
	{
		return value == null ? 0L : ((Number) value).longValue();
	}

	private static OffsetDateTime time(Object value)
	{
		return switch (value)
		{
			case null -> null;
			case OffsetDateTime at -> at;
			case java.sql.Timestamp at -> at.toInstant()
			                                .atOffset(ZoneOffset.UTC);
			case java.time.Instant at -> at.atOffset(ZoneOffset.UTC);
			default -> OffsetDateTime.parse(value.toString());
		};
	}
}
