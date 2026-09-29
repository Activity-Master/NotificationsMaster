package com.guicedee.activitymaster.notifications.test;

import com.guicedee.activitymaster.notifications.NotificationModels.Channel;
import com.guicedee.activitymaster.notifications.channels.INotificationChannel;
import com.guicedee.activitymaster.notifications.channels.INotificationTransport;
import com.guicedee.activitymaster.notifications.channels.NotificationDispatch;
import com.guicedee.activitymaster.notifications.channels.NotificationDispatcher;
import com.guicedee.activitymaster.notifications.channels.WebhookNotificationChannel;
import com.guicedee.activitymaster.notifications.channels.mail.MailNotificationChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the invariant that makes Mail Master genuinely optional.
 * <p>
 * Mail Master is a {@code requires static} dependency, so on a deployment without it every class
 * the notification module loads must be free of Mail Master references — the JVM verifies a method
 * the first time it runs, and a single mention would fail verification even behind a guard that
 * never lets the code execute.
 * <p>
 * Scanning the compiled class file is the honest way to check this. A reference to a type appears
 * in the constant pool as its internal name, so the absence of {@code com/guicedee/activitymaster/mail}
 * in the bytes proves the class can never trigger loading a Mail Master type. If someone later
 * inlines the delivery code back into the channel, this fails.
 */
class MailChannelIsolationTest
{
	private static final String MAIL_INTERNAL_NAME = "com/guicedee/activitymaster/mail";

	@Test
	@DisplayName("Nothing loaded without Mail Master mentions a Mail Master type")
	void optionalDependencyIsIsolated() throws IOException
	{
		for (Class<?> type : new Class<?>[]{MailNotificationChannel.class, INotificationChannel.class,
				INotificationTransport.class, NotificationDispatch.class, NotificationDispatcher.class,
				WebhookNotificationChannel.class})
		{
			assertFalse(referencesMail(type),
					type.getName() + " references a Mail Master type, so it cannot load without Mail Master");
		}
	}

	@Test
	@DisplayName("The delegate that does mention Mail Master is a separate class")
	void mailWorkLivesBehindItsOwnClass() throws Exception
	{
		Class<?> delivery = Class.forName("com.guicedee.activitymaster.notifications.channels.mail.MailDelivery");
		assertTrue(referencesMail(delivery),
				"MailDelivery is meant to be the one class holding the Mail Master references");
		// It is only reachable through the guarded channel, never registered as a channel itself.
		assertFalse(INotificationChannel.class.isAssignableFrom(delivery));
	}

	@Test
	@DisplayName("The mail channel still declares itself, so it can report SKIPPED rather than vanish")
	void channelStillAdvertisesItself()
	{
		MailNotificationChannel channel = new MailNotificationChannel();
		assertEquals(Channel.MAIL, channel.channel());
		assertNotNull(channel.sortOrder());
		// Mail Master is on the test module path, so the probe must find it here.
		assertTrue(MailNotificationChannel.AVAILABLE,
				"Mail Master is a test dependency, so the availability probe should succeed");
	}

	private static boolean referencesMail(Class<?> type) throws IOException
	{
		String resource = "/" + type.getName()
		                            .replace('.', '/') + ".class";
		try (InputStream stream = type.getResourceAsStream(resource))
		{
			assertNotNull(stream, "Cannot read the class file for " + type.getName());
			// ISO-8859-1 maps every byte to one char, so the scan is a faithful byte search.
			String bytes = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
			return bytes.contains(MAIL_INTERNAL_NAME);
		}
	}
}
