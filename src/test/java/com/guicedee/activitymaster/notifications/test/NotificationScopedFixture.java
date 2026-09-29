package com.guicedee.activitymaster.notifications.test;

import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

/**
 * Provisions the reviewed scoped-provider installation and behaviour grants that the production
 * installer deliberately never seeds. Test-only: a real deployment issues these through a
 * privileged administrator, which is exactly what stops a logged-in party publishing to others.
 */
final class NotificationScopedFixture
{
	private final PostgreSQLContainer<?> database;
	private final UUID system;
	private final UUID enterprise;
	private final UUID credential;

	NotificationScopedFixture(PostgreSQLContainer<?> database, UUID system, UUID enterprise, UUID credential)
	{
		this.database = database;
		this.system = system;
		this.enterprise = enterprise;
		this.credential = credential;
	}

	void install() throws Exception
	{
		UUID id = event("Scoped Provider Installation");
		classification(id, "ScopedProvider", "notifications");
		classification(id, "ScopedRealm", "WORK");
		classification(id, "ScopedOwner", enterprise.toString());
	}

	UUID grant(UUID actor, String action) throws Exception
	{
		UUID id = event("Scoped Behavior Grant");
		classification(id, "ScopedProvider", "notifications");
		classification(id, "ScopedRealm", "WORK");
		classification(id, "ScopedOwner", enterprise.toString());
		classification(id, "ScopedBehavior", system + ":" + action);
		sql("""
				insert into event.eventxinvolvedparty
				(eventxinvolvedpartyid,effectivefromdate,effectivetodate,warehousecreatedtimestamp,warehousefromdate,
				 warehouselastupdatedtimestamp,originalsourcesystemuniqueid,value,activeflagid,enterpriseid,
				 systemid,originalsourcesystemid,classificationid,eventid,involvedpartyid)
				select '%s',statement_timestamp()-interval '1 minute','9999-12-31',statement_timestamp(),current_date,
				       statement_timestamp(),'%s','1',f.activeflagid,'%s','%s','%s',c.classificationid,'%s','%s'
				from classification.classification c
				join classification.classificationdataconcept d on d.classificationdataconceptid=c.classificationdataconceptid
				cross join lateral (select activeflagid from dbo.activeflag where enterpriseid='%s' and allowaccess=1 limit 1) f
				where c.classificationname='%s:ScopedActor' and d.classificationdataconceptname='EventXInvolvedParty'
				  and c.enterpriseid='%s' and c.systemid='%s'
				""".formatted(UUID.randomUUID(), id, enterprise, system, system, id, actor, enterprise, system,
				enterprise, system));
		return id;
	}

	void disable(UUID event) throws Exception
	{
		sql("update event.event set effectivetodate=statement_timestamp()-interval '1 second' where eventid='"
				+ event + "'");
	}

	private UUID event(String type) throws Exception
	{
		UUID id = UUID.randomUUID();
		sql("""
				insert into event.event
				(eventid,effectivefromdate,effectivetodate,warehousecreatedtimestamp,warehousefromdate,
				 warehouselastupdatedtimestamp,originalsourcesystemuniqueid,dayid,hourid,minuteid,
				 activeflagid,enterpriseid,systemid,originalsourcesystemid)
				select '%s',statement_timestamp()-interval '1 minute','9999-12-31',statement_timestamp(),current_date,
				       statement_timestamp(),'%s',0,0,0,f.activeflagid,'%s','%s','%s'
				from dbo.activeflag f where f.enterpriseid='%s' and f.allowaccess=1 limit 1
				""".formatted(id, id, enterprise, system, system, enterprise));
		sql("""
				insert into event.eventxeventtype
				(eventxeventtypeid,effectivefromdate,effectivetodate,warehousecreatedtimestamp,warehousefromdate,
				 warehouselastupdatedtimestamp,originalsourcesystemuniqueid,value,activeflagid,enterpriseid,
				 systemid,originalsourcesystemid,classificationid,eventid,eventtypeid)
				select '%s',statement_timestamp()-interval '1 minute','9999-12-31',statement_timestamp(),current_date,
				       statement_timestamp(),'%s','1',f.activeflagid,'%s','%s','%s',c.classificationid,'%s',t.eventtypeid
				from event.eventtype t join classification.classification c
				  on c.classificationname='%s:ScopedEventType' and c.enterpriseid=t.enterpriseid and c.systemid=t.systemid
				cross join lateral (select activeflagid from dbo.activeflag where enterpriseid='%s' and allowaccess=1 limit 1) f
				where t.eventtypename='%s:%s' and t.enterpriseid='%s' and t.systemid='%s'
				""".formatted(UUID.randomUUID(), id, enterprise, system, system, id, system, enterprise, system, type,
				enterprise, system));
		sql("""
				insert into event.eventsecuritytoken
				(eventssecuritytokenid,effectivefromdate,effectivetodate,warehousecreatedtimestamp,warehousefromdate,
				 warehouselastupdatedtimestamp,createallowed,deleteallowed,originalsourcesystemuniqueid,
				 readallowed,updateallowed,activeflagid,enterpriseid,originalsourcesystemid,
				 securitytokenid,systemid,eventsid)
				select '%s',statement_timestamp()-interval '1 minute','9999-12-31',statement_timestamp(),current_date,
				       statement_timestamp(),0,0,'%s',1,0,f.activeflagid,'%s','%s',st.securitytokenid,'%s','%s'
				from security.securitytoken st
				cross join lateral (select activeflagid from dbo.activeflag where enterpriseid='%s' and allowaccess=1 limit 1) f
				where st.securitytoken='%s' and st.enterpriseid='%s'
				""".formatted(UUID.randomUUID(), id, enterprise, system, system, id, enterprise, credential,
				enterprise));
		return id;
	}

	private void classification(UUID event, String name, String value) throws Exception
	{
		sql("""
				insert into event.eventxclassification
				(eventxclassificationid,effectivefromdate,effectivetodate,warehousecreatedtimestamp,warehousefromdate,
				 warehouselastupdatedtimestamp,originalsourcesystemuniqueid,value,activeflagid,enterpriseid,
				 systemid,originalsourcesystemid,classificationid,eventid)
				select '%s',statement_timestamp()-interval '1 minute','9999-12-31',statement_timestamp(),current_date,
				       statement_timestamp(),'%s','%s',f.activeflagid,'%s','%s','%s',c.classificationid,'%s'
				from classification.classification c
				cross join lateral (select activeflagid from dbo.activeflag where enterpriseid='%s' and allowaccess=1 limit 1) f
				where c.classificationname='%s:%s' and c.enterpriseid='%s' and c.systemid='%s'
				""".formatted(UUID.randomUUID(), event, value, enterprise, system, system, event, enterprise, system,
				name, enterprise, system));
	}

	private void sql(String statement) throws Exception
	{
		var result = database.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-U", database.getUsername(), "-d",
				database.getDatabaseName(), "-c", statement);
		if (result.getExitCode() != 0)
		{
			throw new AssertionError(result.getStderr());
		}
		if (statement.stripLeading()
		             .startsWith("insert") && result.getStdout()
		                                            .contains("INSERT 0 0"))
		{
			throw new AssertionError("Missing scoped fixture taxonomy: " + statement);
		}
	}
}
