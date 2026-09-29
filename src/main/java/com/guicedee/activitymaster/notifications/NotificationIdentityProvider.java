package com.guicedee.activitymaster.notifications;

import com.google.inject.ImplementedBy;
import io.smallrye.mutiny.Uni;

/**
 * The consuming host binds this to its authenticated, call-scoped identity.
 * <p>
 * The default denies every request. A deployment that has not wired a real provider therefore has a
 * notification API that answers 401 rather than one that trusts a request body.
 */
@ImplementedBy(NotificationIdentityProvider.Deny.class)
public interface NotificationIdentityProvider
{
	/**
	 * @return the verified identity for the current call
	 */
	Uni<NotificationIdentity> current();

	/** The fail-closed default. */
	final class Deny implements NotificationIdentityProvider
	{
		@Override
		public Uni<NotificationIdentity> current()
		{
			return Uni.createFrom()
			          .failure(new SecurityException("Authenticated notification identity required"));
		}
	}
}
