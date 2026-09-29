package com.guicedee.activitymaster.notifications.channels.mail;

import com.google.inject.ImplementedBy;
import com.guicedee.activitymaster.mail.servers.MailServer;
import io.smallrye.mutiny.Uni;

import java.util.Optional;
import java.util.UUID;

/**
 * Where a recipient can be reached by email, and which relay to use. The host binds this.
 * <p>
 * This lives in the optional {@code channels.mail} package because it names Mail Master types. A
 * deployment without Mail Master on the module path never loads it, and the mail channel reports
 * every dispatch as {@code SKIPPED}.
 * <p>
 * Neither the address nor the server ever comes from a notification, so publishing cannot choose
 * who gets mailed or which relay is used.
 */
@ImplementedBy(IMailNotificationTransport.None.class)
public interface IMailNotificationTransport
{
	/**
	 * A resolved email destination.
	 *
	 * @param server      the SMTP server to send through
	 * @param fromAddress the envelope sender
	 * @param fromName    the sender display name, or {@code null}
	 * @param toAddress   the recipient address
	 */
	record MailTarget(MailServer<?> server, String fromAddress, String fromName, String toAddress)
	{
	}

	/**
	 * Resolves the email destination for a recipient.
	 *
	 * @param enterpriseId the enterprise
	 * @param recipientId  the addressed party
	 * @return the destination, or empty to skip the mail channel for this recipient
	 */
	default Uni<Optional<MailTarget>> mail(UUID enterpriseId, UUID recipientId)
	{
		return Uni.createFrom()
		          .item(Optional.empty());
	}

	/** The fail-quiet default: no email destinations. */
	final class None implements IMailNotificationTransport
	{
	}
}
