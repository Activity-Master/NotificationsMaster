package com.guicedee.activitymaster.notifications;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable service and transport values. Identity and context are supplied by the host, never by a
 * request body, so nothing here carries an actor.
 */
public final class NotificationModels
{
	/** Longest subject a notification may carry. */
	public static final int MAX_SUBJECT = 512;

	/** Longest body a notification may carry, in UTF-16 code units. */
	public static final int MAX_BODY = 65_536;

	/** Longest structured payload a notification may carry, in UTF-16 code units. */
	public static final int MAX_DATA = 16_384;

	/** Longest category name a notification may carry. */
	public static final int MAX_CATEGORY = 128;

	/** Most recipients one publish may address. */
	public static final int MAX_RECIPIENTS = 500;

	private NotificationModels()
	{
	}

	/** How loudly a notification asks to be treated. Purely advisory; it gates no authorisation. */
	public enum Severity
	{
		INFO, SUCCESS, WARNING, CRITICAL
	}

	/**
	 * The recipient's position on a notification.
	 * <p>
	 * {@code UNREAD} is the absence of any state event, not a stored value. The other three are
	 * recorded as append-only state Events, and the latest live one wins.
	 */
	public enum State
	{
		UNREAD, READ, DISMISSED, ACKNOWLEDGED
	}

	/** Where a notification is delivered. {@code STORE} is the FSDM record itself. */
	public enum Channel
	{
		STORE, MAIL, WEBHOOK, EVENT_BUS
	}

	/** The outcome of one delivery attempt on one channel for one recipient. */
	public enum DeliveryResult
	{
		SENT, FAILED, SKIPPED
	}

	/**
	 * A request to publish a notification.
	 *
	 * @param category   a caller-defined grouping, used for filtering and mute preferences
	 * @param severity   defaults to {@link Severity#INFO} when absent
	 * @param subject    a short single-line summary
	 * @param body       the plain Unicode body; renderers must escape it
	 * @param data       an optional JSON object of structured payload, as text
	 * @param recipients the parties to address; duplicates are collapsed
	 * @param channels   the channels to attempt beyond {@link Channel#STORE}; empty means store only
	 */
	public record Publish(String category, Severity severity, String subject, String body, String data,
	                      List<UUID> recipients, List<Channel> channels)
	{
		public Publish
		{
			recipients = recipients == null ? List.of()
					: Collections.unmodifiableList(new ArrayList<>(recipients));
			channels = channels == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(channels));
		}
	}

	/**
	 * A notification as one recipient sees it.
	 *
	 * @param id          the notification event id
	 * @param category    the caller-defined grouping
	 * @param severity    the declared severity
	 * @param subject     the summary line
	 * @param body        the body, present on a single read and omitted from list responses
	 * @param data        the structured payload, or {@code null}
	 * @param publisherId the party that published it
	 * @param createdAt   when it was published
	 * @param state       this recipient's current state
	 */
	public record Notification(UUID id, String category, Severity severity, String subject, String body, String data,
	                           UUID publisherId, OffsetDateTime createdAt, State state)
	{
	}

	/**
	 * One recorded delivery attempt.
	 *
	 * @param id           the delivery event id
	 * @param notification the notification it belongs to
	 * @param recipientId  the addressed party
	 * @param channel      the channel attempted
	 * @param result       the outcome
	 * @param detail       a short, non-sensitive explanation
	 * @param attemptedAt  when the attempt was recorded
	 */
	public record Delivery(UUID id, UUID notification, UUID recipientId, Channel channel, DeliveryResult result,
	                       String detail, OffsetDateTime attemptedAt)
	{
	}

	/**
	 * What one publish produced.
	 *
	 * @param id         the notification event id
	 * @param recipients the parties actually addressed
	 */
	public record Published(UUID id, List<UUID> recipients)
	{
		public Published
		{
			recipients = List.copyOf(recipients);
		}
	}

	/**
	 * Unread and total counts for the calling recipient.
	 * <p>
	 * Counting is deliberately bounded. A badge like this is requested on every page load, so it
	 * considers only the newest slice of the recipient's notifications rather than aggregating an
	 * unbounded history. When {@code capped} is true the real figures are at least these, and a
	 * client should render something like {@code 999+}.
	 *
	 * @param unread notifications with no live state event
	 * @param total  notifications addressed to the caller that are not dismissed
	 * @param capped whether the bounded window was filled, so the counts are lower bounds
	 */
	public record Counts(long unread, long total, boolean capped)
	{
	}

	/**
	 * A bounded page. No unfiltered total is exposed.
	 *
	 * @param items   the page contents
	 * @param offset  the requested offset
	 * @param limit   the requested limit
	 * @param hasMore whether a further page exists
	 * @param <T>     the item type
	 */
	public record Page<T>(List<T> items, int offset, int limit, boolean hasMore)
	{
		public Page
		{
			items = List.copyOf(items);
		}
	}
}
