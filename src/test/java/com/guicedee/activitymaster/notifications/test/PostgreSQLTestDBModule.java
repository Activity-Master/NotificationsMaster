package com.guicedee.activitymaster.notifications.test;

import com.guicedee.activitymaster.fsdm.db.FsdmSchema;
import com.guicedee.client.services.lifecycle.IGuiceModule;
import com.guicedee.persistence.ConnectionBaseInfo;
import com.guicedee.persistence.DatabaseModule;
import com.guicedee.persistence.annotations.EntityManager;
import com.guicedee.persistence.implementations.postgres.PostgresConnectionBaseInfo;
import org.hibernate.jpa.boot.spi.PersistenceUnitDescriptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Creates the test database from the ordered FSDM schema scripts.
 * <p>
 * The script list comes from {@link FsdmSchema}, which is the single definition of how an
 * ActivityMaster database is built. This module used to copy a {@code postgres_fsdm.sql} plus
 * {@code postgres_structure.sql} pair out of another module's test resources; those were a dump of
 * whatever a live database happened to contain, and every module carried its own copy, so they
 * drifted from the maintained scripts.
 * <p>
 * Scripts are read as bytes and streamed into the container rather than mounted from a classpath
 * file, so it makes no difference whether core is an exploded directory or a jar.
 */
@EntityManager(value = "ActivityMaster-Test", defaultEm = true)
public class PostgreSQLTestDBModule extends DatabaseModule<PostgreSQLTestDBModule>
		implements IGuiceModule<PostgreSQLTestDBModule>
{
	static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("fsdm")
			.withUsername("postgres")
			.withPassword("postgres");

	static
	{
		DATABASE.start();
		try
		{
			FsdmSchema.forEachScript((script, sql) -> {
				DATABASE.copyFileToContainer(Transferable.of(sql.getBytes(StandardCharsets.UTF_8)),
						"/tmp/" + script);
				var result = DATABASE.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-U", DATABASE.getUsername(),
						"-d", DATABASE.getDatabaseName(), "-f", "/tmp/" + script);
				if (result.getExitCode() != 0)
				{
					throw new IllegalStateException("psql failed on " + script + ": " + result.getStderr());
				}
			});
		}
		catch (RuntimeException e)
		{
			DATABASE.stop();
			throw new ExceptionInInitializerError(e);
		}
	}

	@Override
	protected String getPersistenceUnitName()
	{
		return "ActivityMaster-Test";
	}

	@Override
	protected String getJndiMapping()
	{
		return "jdbc:activitymaster-test";
	}

	@Override
	public Integer sortOrder()
	{
		return 10;
	}

	@Override
	protected ConnectionBaseInfo getConnectionBaseInfo(PersistenceUnitDescriptor unit, Properties properties)
	{
		var info = new PostgresConnectionBaseInfo();
		info.setServerName(DATABASE.getHost());
		info.setPort(String.valueOf(DATABASE.getFirstMappedPort()));
		info.setDatabaseName(DATABASE.getDatabaseName());
		info.setUsername(DATABASE.getUsername());
		info.setPassword(DATABASE.getPassword());
		info.setDefaultConnection(true);
		info.setReactive(true);
		return info;
	}
}
