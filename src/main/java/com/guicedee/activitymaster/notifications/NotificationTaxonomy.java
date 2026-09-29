package com.guicedee.activitymaster.notifications;

/**
 * The FSDM names the installer creates and the service resolves.
 * <p>
 * These are the only identifiers the module ever puts near a query, and none of them is derived
 * from caller input. Keeping them in one place is what makes it possible to prove that the
 * installer and the service agree on every type, role and data concept.
 */
public final class NotificationTaxonomy
{
	// ── Event types ──────────────────────────────────────────────────────────────────────────────

	/** The notification itself. */
	public static final String NOTIFICATION_EVENT = "Notification";

	/** A recipient state transition: read, dismissed or acknowledged. */
	public static final String STATE_EVENT = "Notification State";

	/** One channel delivery attempt for one recipient. */
	public static final String DELIVERY_EVENT = "Notification Delivery";

	// ── Resource item types ──────────────────────────────────────────────────────────────────────

	/** The private resource item holding a notification body and payload. */
	public static final String BODY_RESOURCE = "Notification Body";

	// ── Notification roles ───────────────────────────────────────────────────────────────────────

	/** {@code EventXEventType} — links a notification to its type. */
	public static final String NOTIFICATION_TYPE_ROLE = "NotificationType";

	/** {@code EventXInvolvedParty} — the party a notification is addressed to. */
	public static final String RECIPIENT_ROLE = "NotificationRecipient";

	/** {@code EventXInvolvedParty} — the party that published a notification. */
	public static final String PUBLISHER_ROLE = "NotificationPublisher";

	/** {@code EventXClassification} — the caller-defined category, as the link value. */
	public static final String CATEGORY_ROLE = "NotificationCategory";

	/** {@code EventXClassification} — the severity, as the link value. */
	public static final String SEVERITY_ROLE = "NotificationSeverity";

	/** {@code EventXClassification} — the subject line, as the link value. */
	public static final String SUBJECT_ROLE = "NotificationSubject";

	/** {@code EventXClassification} — the publishing context, as the link value. */
	public static final String CONTEXT_ROLE = "NotificationContext";

	/**
	 * {@code EventXResourceItem} — links a notification to the private resource item holding its
	 * body and payload. The content is that item's <em>data</em>: relationship values are
	 * {@code varchar(150)} and are for short discriminators, not for bodies.
	 */
	public static final String BODY_ROLE = "NotificationBody";

	// ── State roles ──────────────────────────────────────────────────────────────────────────────

	/** {@code EventXEventType} — links a state event to its type. */
	public static final String STATE_TYPE_ROLE = "NotificationStateType";

	/** {@code EventXClassification} — the recorded state, as the link value. */
	public static final String STATE_ROLE = "NotificationState";

	/** {@code EventXEvent} — links a state event (child) to its notification (parent). */
	public static final String STATE_OF_ROLE = "NotificationStateOf";

	/** {@code EventXInvolvedParty} — the party whose state this is. */
	public static final String STATE_ACTOR_ROLE = "NotificationStateActor";

	// ── Delivery roles ───────────────────────────────────────────────────────────────────────────

	/** {@code EventXEventType} — links a delivery event to its type. */
	public static final String DELIVERY_TYPE_ROLE = "NotificationDeliveryType";

	/** {@code EventXClassification} — the channel attempted, as the link value. */
	public static final String DELIVERY_CHANNEL_ROLE = "NotificationDeliveryChannel";

	/** {@code EventXClassification} — the outcome, as the link value. */
	public static final String DELIVERY_RESULT_ROLE = "NotificationDeliveryResult";

	/** {@code EventXClassification} — a short explanation, as the link value. */
	public static final String DELIVERY_DETAIL_ROLE = "NotificationDeliveryDetail";

	/** {@code EventXEvent} — links a delivery event (child) to its notification (parent). */
	public static final String DELIVERY_OF_ROLE = "NotificationDeliveryOf";

	/** {@code EventXInvolvedParty} — the party the attempt was for. */
	public static final String DELIVERY_RECIPIENT_ROLE = "NotificationDeliveryRecipient";

	private NotificationTaxonomy()
	{
	}
}
