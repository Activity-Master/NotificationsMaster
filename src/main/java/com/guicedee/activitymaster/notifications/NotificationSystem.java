package com.guicedee.activitymaster.notifications;

import com.google.inject.Inject;
import com.guicedee.activitymaster.fsdm.client.services.ISystemsService;
import com.guicedee.activitymaster.fsdm.client.services.administration.MasterDefaultSystem;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.client.services.systems.IMasterSystem;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;

/**
 * Registers the {@code Notification Master} system for an enterprise.
 */
public final class NotificationSystem extends MasterDefaultSystem<NotificationSystem>
		implements IMasterSystem<NotificationSystem>
{
	/** The registered system name. */
	public static final String NAME = "Notification Master";

	@Inject
	private ISystemsService<?> systems;

	@Override
	public Uni<ISystems<?, ?>> registerSystem(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise)
	{
		return systems.create(session, enterprise, getSystemName(), getSystemDescription())
		              .chain(system -> systems.registerNewSystem(session, enterprise, system)
		                                      .replaceWith(system));
	}

	@Override
	public Uni<Void> createDefaults(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise)
	{
		return Uni.createFrom()
		          .voidItem();
	}

	@Override
	public Uni<Void> postStartup(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise)
	{
		return Uni.createFrom()
		          .voidItem();
	}

	@Override
	public String getSystemName()
	{
		return NAME;
	}

	@Override
	public String getSystemDescription()
	{
		return "Recipient-addressed notifications, state and channel delivery";
	}

	@Override
	public Integer sortOrder()
	{
		return 1400;
	}

	@Override
	public int totalTasks()
	{
		return 1;
	}
}
