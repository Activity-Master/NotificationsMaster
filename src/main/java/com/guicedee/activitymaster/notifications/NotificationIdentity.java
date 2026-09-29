package com.guicedee.activitymaster.notifications;

import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;

import java.util.Objects;
import java.util.UUID;

/**
 * A host-resolved actor and FSDM context. This is never request body data.
 *
 * @param partyId       the verified acting involved party
 * @param enterpriseId  the enterprise the call is scoped to
 * @param context       the verified realm and owner
 * @param identityToken the ActivityMaster identifying credential of the actor
 */
public record NotificationIdentity(UUID partyId, UUID enterpriseId, ActivityScope.Context context,
                                   UUID identityToken)
{
	public NotificationIdentity
	{
		Objects.requireNonNull(partyId, "partyId");
		Objects.requireNonNull(enterpriseId, "enterpriseId");
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(identityToken, "identityToken");
		if (context.realm() == ActivityScope.Realm.WORK
				? !enterpriseId.equals(context.ownerId())
				: !partyId.equals(context.ownerId()))
		{
			throw new SecurityException("Notification context owner mismatch");
		}
	}

	/**
	 * @return the caller token array passed to FSDM authorisation
	 */
	public UUID[] tokens()
	{
		return new UUID[]{identityToken};
	}
}
