package com.guicedee.activitymaster.notifications.rest;

import com.google.inject.Inject;
import com.guicedee.activitymaster.notifications.NotificationApi;
import com.guicedee.activitymaster.notifications.NotificationModels.Counts;
import com.guicedee.activitymaster.notifications.NotificationModels.Delivery;
import com.guicedee.activitymaster.notifications.NotificationModels.Notification;
import com.guicedee.activitymaster.notifications.NotificationModels.Page;
import com.guicedee.activitymaster.notifications.NotificationModels.Publish;
import com.guicedee.activitymaster.notifications.NotificationModels.Published;
import com.guicedee.activitymaster.notifications.NotificationModels.State;
import io.smallrye.mutiny.Uni;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

/**
 * REST surface for Notification Master.
 * <p>
 * Every operation acts as the authenticated caller resolved by the host's
 * {@code NotificationIdentityProvider}. No path or body parameter names the actor, so a client
 * cannot read or change another party's notifications by editing a request.
 * <p>
 * Publishing needs the {@code notifications.publish} behaviour grant and reading delivery attempts
 * needs {@code notifications.audit}; everything else is available to the recipient of the
 * notification in question.
 */
@Path("{enterprise}/notifications")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Notifications", description = "Recipient-addressed notifications, state and channel delivery")
@ApiResponse(responseCode = "400", description = "Invalid input")
@ApiResponse(responseCode = "401", description = "Trusted caller identity missing")
@ApiResponse(responseCode = "403", description = "Behaviour grant or enterprise scope denied")
@ApiResponse(responseCode = "404", description = "No such notification is addressed to the caller")
public final class NotificationRestService
{
	@Inject
	private NotificationApi api;

	@POST
	@Operation(summary = "Publish a notification to one or more recipients",
			description = "Requires the notifications.publish behaviour grant. Returns once the notification is "
					+ "durable; channel delivery continues asynchronously and is readable through deliveries.")
	public Uni<Published> publish(@Parameter(description = "Owning enterprise name")
	                              @PathParam("enterprise") String enterprise, Publish request)
	{
		return api.publish(enterprise, request);
	}

	@GET
	@Operation(summary = "List notifications addressed to the caller",
			description = "Newest first, bodies omitted. With no state filter, dismissed notifications are excluded.")
	public Uni<Page<Notification>> list(@PathParam("enterprise") String enterprise,
	                                    @Parameter(description = "UNREAD, READ, DISMISSED or ACKNOWLEDGED")
	                                    @QueryParam("state") State state,
	                                    @Parameter(description = "Exact category match")
	                                    @QueryParam("category") String category,
	                                    @QueryParam("offset") @DefaultValue("0") int offset,
	                                    @QueryParam("limit") @DefaultValue("50") int limit)
	{
		return api.list(enterprise, state, category, offset, limit);
	}

	@GET
	@Path("counts")
	@Operation(summary = "Count the caller's unread and undismissed notifications")
	public Uni<Counts> counts(@PathParam("enterprise") String enterprise)
	{
		return api.counts(enterprise);
	}

	@GET
	@Path("{id}")
	@Operation(summary = "Read one notification addressed to the caller, including its body")
	public Uni<Notification> find(@PathParam("enterprise") String enterprise, @PathParam("id") UUID id)
	{
		return api.find(enterprise, id);
	}

	@POST
	@Path("{id}/read")
	@Operation(summary = "Mark a notification read", description = "Idempotent; repeating it writes nothing.")
	public Uni<Notification> read(@PathParam("enterprise") String enterprise, @PathParam("id") UUID id)
	{
		return api.transition(enterprise, id, State.READ);
	}

	@POST
	@Path("{id}/dismiss")
	@Operation(summary = "Dismiss a notification so it leaves the default inbox view")
	public Uni<Notification> dismiss(@PathParam("enterprise") String enterprise, @PathParam("id") UUID id)
	{
		return api.transition(enterprise, id, State.DISMISSED);
	}

	@POST
	@Path("{id}/acknowledge")
	@Operation(summary = "Acknowledge a notification that asks for a positive response")
	public Uni<Notification> acknowledge(@PathParam("enterprise") String enterprise, @PathParam("id") UUID id)
	{
		return api.transition(enterprise, id, State.ACKNOWLEDGED);
	}

	@POST
	@Path("read-all")
	@Operation(summary = "Mark every unread notification read",
			description = "Bounded per call; repeat until the returned count is zero.")
	public Uni<Long> readAll(@PathParam("enterprise") String enterprise,
	                         @Parameter(description = "Exact category match")
	                         @QueryParam("category") String category)
	{
		return api.readAll(enterprise, category);
	}

	@GET
	@Path("{id}/deliveries")
	@Operation(summary = "List the recorded channel delivery attempts for a notification",
			description = "Requires the notifications.audit behaviour grant.")
	public Uni<Page<Delivery>> deliveries(@PathParam("enterprise") String enterprise, @PathParam("id") UUID id,
	                                      @QueryParam("offset") @DefaultValue("0") int offset,
	                                      @QueryParam("limit") @DefaultValue("50") int limit)
	{
		return api.deliveries(enterprise, id, offset, limit);
	}
}
