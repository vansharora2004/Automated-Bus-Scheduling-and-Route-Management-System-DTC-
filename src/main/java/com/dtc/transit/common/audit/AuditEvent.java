package com.dtc.transit.common.audit;

/**
 * A single auditable change, published by a service and written by {@link AuditEventListener}.
 *
 * <p>The actor and trace id are deliberately absent. They are resolved when the row is written, from
 * the security context and the MDC, so no caller can claim to be someone else.
 *
 * @param action     what happened, for example {@code USER_CREATED}
 * @param entityType the kind of entity affected, for example {@code APP_USER}
 * @param entityId   the entity's identifier, as text so every id type fits
 * @param before     JSON state before the change, or null for a create
 * @param after      JSON state after the change, or null for a delete
 * @param reason     the reason the actor gave, where the operation requires one
 */
public record AuditEvent(
        String action, String entityType, String entityId, String before, String after, String reason) {

    public static AuditEvent created(String entityType, Object entityId, String after) {
        return new AuditEvent(entityType + "_CREATED", entityType, String.valueOf(entityId), null, after, null);
    }

    public static AuditEvent updated(String entityType, Object entityId, String before, String after) {
        return new AuditEvent(
                entityType + "_UPDATED", entityType, String.valueOf(entityId), before, after, null);
    }

    public static AuditEvent of(String action, String entityType, Object entityId) {
        return new AuditEvent(action, entityType, String.valueOf(entityId), null, null, null);
    }
}
