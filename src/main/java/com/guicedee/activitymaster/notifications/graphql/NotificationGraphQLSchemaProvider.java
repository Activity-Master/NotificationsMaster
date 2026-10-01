package com.guicedee.activitymaster.notifications.graphql;

import com.guicedee.activitymaster.notifications.NotificationApi;
import com.guicedee.activitymaster.notifications.NotificationModels.*;
import com.guicedee.client.IGuiceContext;
import com.guicedee.vertx.graphql.services.IGraphQLSchemaProvider;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import java.util.List;
import java.util.Map;

import static com.guicedee.activitymaster.fsdm.graphql.ActivityMasterGraphQLAdapter.*;

/** Recipient GraphQL surface; publishing and delivery audit grants stay in NotificationApi. */
public final class NotificationGraphQLSchemaProvider implements IGraphQLSchemaProvider<NotificationGraphQLSchemaProvider> {
    private final NotificationApi suppliedApi;
    public NotificationGraphQLSchemaProvider() { suppliedApi = null; }
    public NotificationGraphQLSchemaProvider(NotificationApi api) { suppliedApi = api; }
    private NotificationApi api() { return suppliedApi == null ? IGuiceContext.get(NotificationApi.class) : suppliedApi; }

    private static final String SDL = """
        scalar NotificationLong
        enum NotificationSeverity { INFO SUCCESS WARNING CRITICAL }
        enum NotificationState { UNREAD READ DISMISSED ACKNOWLEDGED }
        enum NotificationChannel { STORE MAIL WEBHOOK EVENT_BUS }
        enum NotificationDeliveryResult { SENT FAILED SKIPPED }
        type Notification {
            id: ID!, category: String!, severity: NotificationSeverity!, subject: String!, body: String, data: String,
            publisherId: ID!, createdAt: String!, state: NotificationState!
        }
        type NotificationPublished { id: ID!, recipients: [ID!]! }
        type NotificationCounts { unread: NotificationLong!, total: NotificationLong!, capped: Boolean! }
        type NotificationDelivery {
            id: ID!, notification: ID!, recipientId: ID!, channel: NotificationChannel!,
            result: NotificationDeliveryResult!, detail: String, attemptedAt: String!
        }
        type NotificationPage { items: [Notification!]!, offset: Int!, limit: Int!, hasMore: Boolean! }
        type NotificationDeliveryPage { items: [NotificationDelivery!]!, offset: Int!, limit: Int!, hasMore: Boolean! }
        input NotificationPublishInput {
            category: String!, severity: NotificationSeverity = INFO, subject: String!, body: String!, data: String,
            recipients: [ID!]!, channels: [NotificationChannel!]! = []
        }
        extend type Query {
            notification(enterprise: String!, notificationId: ID!): Notification!
            notifications(enterprise: String!, state: NotificationState, category: String, offset: Int! = 0, limit: Int! = 50): NotificationPage!
            notificationCounts(enterprise: String!): NotificationCounts!
            notificationDeliveries(enterprise: String!, notificationId: ID!, offset: Int! = 0, limit: Int! = 50): NotificationDeliveryPage!
        }
        extend type Mutation {
            notificationPublish(enterprise: String!, input: NotificationPublishInput!): NotificationPublished!
            notificationRead(enterprise: String!, notificationId: ID!): Notification!
            notificationDismiss(enterprise: String!, notificationId: ID!): Notification!
            notificationAcknowledge(enterprise: String!, notificationId: ID!): Notification!
            notificationReadAll(enterprise: String!, category: String): NotificationLong!
        }
        """;

    @Override public TypeDefinitionRegistry getTypeDefinitions() { return new SchemaParser().parse(SDL); }
    @Override public RuntimeWiring.Builder configureWiring(RuntimeWiring.Builder builder) {
        return builder.scalar(NotificationLongScalar.create()).type("Query", q -> q
                .dataFetcher("notification", fetch("Notification", e -> api().find(e.getArgument("enterprise"), id(e, "notificationId"))))
                .dataFetcher("notifications", fetch("Notification", e -> {
                    String state = e.getArgument("state");
                    return api().list(e.getArgument("enterprise"), state == null ? null : State.valueOf(state),
                            e.getArgument("category"), e.getArgument("offset"), e.getArgument("limit"));
                }))
                .dataFetcher("notificationCounts", fetch("Notification", e -> api().counts(e.getArgument("enterprise"))))
                .dataFetcher("notificationDeliveries", fetch("Notification", e -> api().deliveries(e.getArgument("enterprise"), id(e, "notificationId"), e.getArgument("offset"), e.getArgument("limit")))))
            .type("Mutation", m -> m
                .dataFetcher("notificationPublish", fetch("Notification", e -> {
                    Map<String, Object> input = e.getArgument("input");
                    String severity = (String) input.get("severity");
                    return api().publish(e.getArgument("enterprise"), new Publish((String) input.get("category"),
                            severity == null ? null : Severity.valueOf(severity), (String) input.get("subject"),
                            (String) input.get("body"), (String) input.get("data"), ids(strings(input, "recipients")),
                            strings(input, "channels").stream().map(Channel::valueOf).toList()));
                }))
                .dataFetcher("notificationRead", fetch("Notification", e -> api().transition(e.getArgument("enterprise"), id(e, "notificationId"), State.READ)))
                .dataFetcher("notificationDismiss", fetch("Notification", e -> api().transition(e.getArgument("enterprise"), id(e, "notificationId"), State.DISMISSED)))
                .dataFetcher("notificationAcknowledge", fetch("Notification", e -> api().transition(e.getArgument("enterprise"), id(e, "notificationId"), State.ACKNOWLEDGED)))
                .dataFetcher("notificationReadAll", fetch("Notification", e -> api().readAll(e.getArgument("enterprise"), e.getArgument("category")))));
    }
    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> input, String key) { return (List<String>) input.get(key); }
}
