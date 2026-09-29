package com.guicedee.activitymaster.notifications;

import com.guicedee.activitymaster.fsdm.client.services.IClassificationService;
import com.guicedee.activitymaster.fsdm.client.services.IEventService;
import com.guicedee.activitymaster.fsdm.client.services.IResourceItemService;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.client.services.classifications.EnterpriseClassificationDataConcepts;
import com.guicedee.activitymaster.fsdm.client.services.systems.ISystemUpdate;
import com.guicedee.activitymaster.fsdm.client.services.systems.SortedUpdate;
import com.guicedee.activitymaster.fsdm.transactions.FsdmBehaviorTaxonomy;
import com.guicedee.client.IGuiceContext;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;

import java.util.UUID;

import static com.guicedee.activitymaster.fsdm.client.services.IActivityMasterService.getISystem;
import static com.guicedee.activitymaster.fsdm.client.services.IActivityMasterService.getISystemToken;
import static com.guicedee.activitymaster.notifications.NotificationTaxonomy.*;

/**
 * Installs only FSDM taxonomy. No notification is ever created at startup.
 * <p>
 * Bodies and structured payloads are the data of a private resource item, not relationship
 * values, so nothing here depends on the width of a link column.
 */
@SortedUpdate(sortOrder = 1400, taskCount = 1)
public final class NotificationInstall implements ISystemUpdate
{
	@Override
	public Uni<Boolean> update(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise)
	{
		return getISystem(session, NotificationSystem.NAME, enterprise)
		               .chain(system -> getISystemToken(session, NotificationSystem.NAME, enterprise)
				               .chain(token -> install(session, system, token)))
		               .invoke(() -> logProgress("Notification Master", "Installed notification taxonomy", 1))
		               .replaceWith(Boolean.TRUE);
	}

	private Uni<Void> install(Mutiny.StatelessSession session, ISystems<?, ?> system, UUID token)
	{
		IEventService<?> events = IGuiceContext.get(IEventService.class);
		IResourceItemService<?> resources = IGuiceContext.get(IResourceItemService.class);
		IClassificationService<?> classes = IGuiceContext.get(IClassificationService.class);

		// The scoped-behaviour vocabulary this system's publish and audit grants are expressed in.
		// Vocabulary only: no installation event and no actor grant is ever seeded, so a fresh
		// enterprise has nobody who can publish until an administrator issues the grants.
		return FsdmBehaviorTaxonomy.ensure(session, system, token)
		             .chain(() -> events.createEventType(session, NOTIFICATION_EVENT, system, token))
		             .chain(() -> events.createEventType(session, STATE_EVENT, system, token))
		             .chain(() -> events.createEventType(session, DELIVERY_EVENT, system, token))
		             .chain(() -> resources.createType(session, BODY_RESOURCE,
				             "Private notification body and payload", system, token))

		             .chain(() -> role(session, classes, system, token, NOTIFICATION_TYPE_ROLE,
				             EnterpriseClassificationDataConcepts.EventXEventType))
		             .chain(() -> role(session, classes, system, token, RECIPIENT_ROLE,
				             EnterpriseClassificationDataConcepts.EventXInvolvedParty))
		             .chain(() -> role(session, classes, system, token, PUBLISHER_ROLE,
				             EnterpriseClassificationDataConcepts.EventXInvolvedParty))
		             .chain(() -> role(session, classes, system, token, CATEGORY_ROLE,
				             EnterpriseClassificationDataConcepts.EventXClassification))
		             .chain(() -> role(session, classes, system, token, SEVERITY_ROLE,
				             EnterpriseClassificationDataConcepts.EventXClassification))
		             .chain(() -> role(session, classes, system, token, SUBJECT_ROLE,
				             EnterpriseClassificationDataConcepts.EventXClassification))
		             .chain(() -> role(session, classes, system, token, CONTEXT_ROLE,
				             EnterpriseClassificationDataConcepts.EventXClassification))
		             .chain(() -> role(session, classes, system, token, BODY_ROLE,
				             EnterpriseClassificationDataConcepts.EventXResourceItem))

		             .chain(() -> role(session, classes, system, token, STATE_TYPE_ROLE,
				             EnterpriseClassificationDataConcepts.EventXEventType))
		             .chain(() -> role(session, classes, system, token, STATE_ROLE,
				             EnterpriseClassificationDataConcepts.EventXClassification))
		             .chain(() -> role(session, classes, system, token, STATE_OF_ROLE,
				             EnterpriseClassificationDataConcepts.EventXEvent))
		             .chain(() -> role(session, classes, system, token, STATE_ACTOR_ROLE,
				             EnterpriseClassificationDataConcepts.EventXInvolvedParty))

		             .chain(() -> role(session, classes, system, token, DELIVERY_TYPE_ROLE,
				             EnterpriseClassificationDataConcepts.EventXEventType))
		             .chain(() -> role(session, classes, system, token, DELIVERY_CHANNEL_ROLE,
				             EnterpriseClassificationDataConcepts.EventXClassification))
		             .chain(() -> role(session, classes, system, token, DELIVERY_RESULT_ROLE,
				             EnterpriseClassificationDataConcepts.EventXClassification))
		             .chain(() -> role(session, classes, system, token, DELIVERY_DETAIL_ROLE,
				             EnterpriseClassificationDataConcepts.EventXClassification))
		             .chain(() -> role(session, classes, system, token, DELIVERY_OF_ROLE,
				             EnterpriseClassificationDataConcepts.EventXEvent))
		             .chain(() -> role(session, classes, system, token, DELIVERY_RECIPIENT_ROLE,
				             EnterpriseClassificationDataConcepts.EventXInvolvedParty))
		             .replaceWithVoid();
	}

	private Uni<?> role(Mutiny.StatelessSession session, IClassificationService<?> classes, ISystems<?, ?> system,
	                    UUID token, String name, EnterpriseClassificationDataConcepts concept)
	{
		return classes.create(session, name, name, concept, system, token);
	}
}
