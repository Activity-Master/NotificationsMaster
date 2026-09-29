package com.guicedee.activitymaster.notifications;

import com.guicedee.client.services.config.IGuiceScanModuleInclusions;

import java.util.Set;

/**
 * Includes the notifications module in the Guice classpath scan.
 */
public final class NotificationInclusionModule
		implements IGuiceScanModuleInclusions<NotificationInclusionModule>
{
	@Override
	public Set<String> includeModules()
	{
		return Set.of("com.guicedee.activitymaster.notifications");
	}
}
