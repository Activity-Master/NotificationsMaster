import com.guicedee.activitymaster.fsdm.client.services.systems.IMasterSystem;
import com.guicedee.activitymaster.fsdm.client.services.systems.ISystemUpdate;
import com.guicedee.activitymaster.notifications.NotificationInclusionModule;
import com.guicedee.activitymaster.notifications.NotificationInstall;
import com.guicedee.activitymaster.notifications.NotificationModule;
import com.guicedee.activitymaster.notifications.NotificationSystem;
import com.guicedee.activitymaster.notifications.channels.EventBusNotificationChannel;
import com.guicedee.activitymaster.notifications.channels.INotificationChannel;
import com.guicedee.activitymaster.notifications.channels.mail.MailNotificationChannel;
import com.guicedee.activitymaster.notifications.channels.WebhookNotificationChannel;
import com.guicedee.client.services.config.IGuiceScanModuleInclusions;
import com.guicedee.client.services.lifecycle.IGuiceModule;

module com.guicedee.activitymaster.notifications {
	requires transitive com.guicedee.activitymaster.fsdm;
	// Optional: without Mail Master the mail channel loads and reports every dispatch SKIPPED.
	requires static com.guicedee.activitymaster.mail;
	requires com.guicedee.rest;
	requires io.vertx.web.client;
	requires io.vertx.core;

	requires static lombok;

	uses INotificationChannel;

	exports com.guicedee.activitymaster.notifications;
	exports com.guicedee.activitymaster.notifications.channels;
	exports com.guicedee.activitymaster.notifications.channels.mail;
	exports com.guicedee.activitymaster.notifications.rest;

	opens com.guicedee.activitymaster.notifications to com.google.guice, tools.jackson.databind;
	opens com.guicedee.activitymaster.notifications.channels to com.google.guice, tools.jackson.databind;
	opens com.guicedee.activitymaster.notifications.channels.mail to com.google.guice, tools.jackson.databind;
	opens com.guicedee.activitymaster.notifications.rest to com.google.guice, com.guicedee.rest,
			tools.jackson.databind, org.hibernate.reactive, net.bytebuddy;

	provides IGuiceModule with NotificationModule;
	provides IGuiceScanModuleInclusions with NotificationInclusionModule;
	provides IMasterSystem with NotificationSystem;
	provides ISystemUpdate with NotificationInstall;
	provides INotificationChannel with MailNotificationChannel, WebhookNotificationChannel,
			EventBusNotificationChannel;
}
