package com.guicedee.activitymaster.notifications.channels;

import com.google.inject.ImplementedBy;
import io.smallrye.mutiny.Uni;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a recipient can actually be reached. The host binds this; the module never guesses.
 * <p>
 * A webhook URL is a target for outbound traffic, so letting one originate anywhere near a request
 * body would turn the module into an SSRF surface. Lookups are keyed by enterprise and party, and
 * the default implementation returns nothing, so a deployment that has not wired a transport
 * delivers to the store only.
 * <p>
 * Email destinations live in the optional {@code channels.mail} package, on
 * {@code IMailNotificationTransport}, so that nothing in this package references a Mail Master
 * type and the module still links when Mail Master is absent.
 */
@ImplementedBy(INotificationTransport.None.class)
public interface INotificationTransport
{
	/** Default Vert.x event bus address notifications are published to. */
	String DEFAULT_EVENT_BUS_ADDRESS = "activitymaster.notifications";

	/**
	 * Resolves the webhook destination for a recipient. The URL must be {@code https}; the channel
	 * rejects anything else rather than trusting the binding.
	 *
	 * @param enterpriseId the enterprise
	 * @param recipientId  the addressed party
	 * @return the destination, or empty to skip the webhook channel for this recipient
	 */
	default Uni<Optional<URI>> webhook(UUID enterpriseId, UUID recipientId)
	{
		return Uni.createFrom()
		          .item(Optional.empty());
	}

	/**
	 * A shared secret sent as {@code X-ActivityMaster-Token} on webhook calls, so a receiver can
	 * verify the caller.
	 *
	 * @param enterpriseId the enterprise
	 * @param recipientId  the addressed party
	 * @return the secret, or empty to send none
	 */
	default Uni<Optional<String>> webhookSecret(UUID enterpriseId, UUID recipientId)
	{
		return Uni.createFrom()
		          .item(Optional.empty());
	}

	/**
	 * The event bus address notifications for this enterprise are published to.
	 *
	 * @param enterpriseId the enterprise
	 * @return the address; never {@code null}
	 */
	default String eventBusAddress(UUID enterpriseId)
	{
		return DEFAULT_EVENT_BUS_ADDRESS + "." + enterpriseId;
	}

	/** The fail-quiet default: store-only delivery. */
	final class None implements INotificationTransport
	{
	}
}
