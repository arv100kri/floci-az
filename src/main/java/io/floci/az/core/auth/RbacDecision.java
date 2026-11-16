package io.floci.az.core.auth;

import java.util.List;

/**
 * The answer {@link RbacEvaluator} gives to one check: whether the operation is allowed, which role assignments
 * allow it, and why, with the question echoed so a caller can log the decision or build its service's 403 from it.
 *
 * @param allowed       whether a role assignment grants the operation
 * @param principalId   the principal's object id, as asked
 * @param scope         the scope, as asked
 * @param operation     the operation, as asked
 * @param assignmentIds the ids of every role assignment that grants the operation; empty when it is denied
 * @param reason        why, as one or two sentences that name the assignments considered
 */
public record RbacDecision(boolean allowed, String principalId, String scope, RbacOperation operation,
                           List<String> assignmentIds, String reason) {

    public RbacDecision {
        assignmentIds = List.copyOf(assignmentIds);
    }
}
