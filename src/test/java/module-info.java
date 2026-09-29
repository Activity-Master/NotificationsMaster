import com.guicedee.client.services.lifecycle.IGuiceModule;

open module activity.master.notification.tests {
	requires com.guicedee.activitymaster.notifications;
	requires com.guicedee.activitymaster.fsdm;
	requires com.guicedee.activitymaster.mail;
	requires com.guicedee.activitymaster.fsdm.client;
	requires com.guicedee.guicedinjection;
	requires com.guicedee.persistence;
	requires com.google.guice;
	requires io.smallrye.mutiny;
	requires org.hibernate.reactive;
	requires org.junit.jupiter.api;
	requires org.testcontainers;
	requires io.vertx.sql.client.pg;
	requires jakarta.ws.rs;

	provides IGuiceModule with com.guicedee.activitymaster.notifications.test.PostgreSQLTestDBModule;
}
