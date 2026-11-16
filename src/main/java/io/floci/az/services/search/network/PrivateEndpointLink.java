package io.floci.az.services.search.network;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One connection a Microsoft.Network private endpoint makes to a search service, as the service's
 * {@code privateEndpointConnections} show it.
 *
 * @param name              the connection's name on the search side: {@code {endpoint name}.{guid}}
 * @param privateEndpointId the ARM id of the private endpoint, in the casing it was created with
 * @param connectionName    the name of the connection within the endpoint
 * @param manual            whether it is one of the endpoint's {@code manualPrivateLinkServiceConnections}
 * @param groupId           the group id it connects to: {@code searchService}
 * @param state             its approval state
 * @param subnetId          the ARM id of the subnet the endpoint sits in, or null when it names none
 */
public record PrivateEndpointLink(String name, String privateEndpointId, String connectionName, boolean manual,
                                  String groupId, State state, String subnetId) {

    /** This connection in another state. */
    public PrivateEndpointLink withState(State newState) {
        return new PrivateEndpointLink(name, privateEndpointId, connectionName, manual, groupId, newState, subnetId);
    }

    /**
     * A connection's {@code privateLinkServiceConnectionState}.
     *
     * @param status          {@code Pending}, {@code Approved}, {@code Rejected} or {@code Disconnected}
     * @param description     why it is in this state
     * @param actionsRequired what the endpoint's owner has to do: {@code None} by default
     */
    public record State(String status, String description, String actionsRequired) {

        public static final String PENDING = "Pending";
        public static final String APPROVED = "Approved";
        public static final String REJECTED = "Rejected";
        public static final String DISCONNECTED = "Disconnected";

        /** The state an endpoint document holds, with the spec's default {@code actionsRequired}. */
        static State of(Map<String, Object> state) {
            return new State(text(state.get("status")), text(state.get("description")),
                state.get("actionsRequired") instanceof String actions ? actions : "None");
        }

        /** This state with another status, keeping its description and required actions. */
        public State withStatus(String newStatus) {
            return new State(newStatus, description, actionsRequired);
        }

        public boolean is(String expected) {
            return expected.equalsIgnoreCase(status);
        }

        /** The JSON object both sides answer: {@code status}, {@code description}, {@code actionsRequired}. */
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("status", status);
            json.put("description", description);
            json.put("actionsRequired", actionsRequired);
            return json;
        }

        private static String text(Object value) {
            return value instanceof String text ? text : "";
        }
    }
}
