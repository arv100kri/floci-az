package io.floci.az.services.search.arm;

import io.floci.az.core.arm.ArmResources;
import io.floci.az.services.search.arm.SearchArmModels.Audit;
import io.floci.az.services.search.arm.SearchArmModels.AuthOptions;
import io.floci.az.services.search.arm.SearchArmModels.NetworkRules;
import io.floci.az.services.search.arm.SearchArmModels.SearchServiceRecord;
import io.floci.az.services.search.arm.SearchArmModels.ServiceIdentity;
import io.floci.az.services.search.arm.SearchArmModels.ServiceSettings;
import io.floci.az.services.search.arm.SearchArmModels.UserIdentity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON of a search service: the {@code SearchService} resource {@code Services_Get} and the lists answer, its
 * entry in the generic resource index, and the {@code authOptions}, {@code networkRuleSet} and {@code identity}
 * shapes the data plane reads. Values the emulator does not model are answered as a running service with nothing
 * pending: {@code status} {@code running}, {@code provisioningState} {@code succeeded}, a compliant CMK status and no
 * upgrade available.
 */
final class SearchServiceDocuments {

    static final String TYPE = SearchArmSurface.NAMESPACE + "/searchServices";

    /** The timestamp of an ETag, with the seven fraction digits Azure's own carry. */
    private static final DateTimeFormatter ETAG_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'").withZone(ZoneOffset.UTC);

    private SearchServiceDocuments() {
    }

    /** The resource, with its endpoint under {@code endpointSuffix}, the search DNS suffix of its cloud. */
    static Map<String, Object> document(SearchServiceRecord service, String endpointSuffix) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", service.id());
        document.put("name", service.name());
        document.put("type", TYPE);
        document.put("location", service.location());
        document.put("tags", service.tags());
        document.put("sku", Map.of("name", service.sku()));
        Map<String, Object> identity = identity(service.identity());
        if (identity != null) {
            document.put("identity", identity);
        }
        document.put("properties", properties(service, endpointSuffix));
        document.put("systemData", systemData(service.audit()));
        return document;
    }

    /**
     * The service's {@code properties}, in the order Azure answers them. The serverless SKU has no replicas,
     * partitions or compute type, so they are left out for it.
     */
    private static Map<String, Object> properties(SearchServiceRecord service, String endpointSuffix) {
        ServiceSettings settings = service.settings();
        Map<String, Object> properties = new LinkedHashMap<>();
        if (settings.replicaCount() != null) {
            properties.put("replicaCount", settings.replicaCount());
        }
        if (settings.partitionCount() != null) {
            properties.put("partitionCount", settings.partitionCount());
        }
        properties.put("endpoint", "https://" + service.name() + "." + endpointSuffix);
        properties.put("status", "running");
        properties.put("statusDetails", "");
        properties.put("provisioningState", "succeeded");
        properties.put("hostingMode", settings.hostingMode());
        if (settings.computeType() != null) {
            properties.put("computeType", settings.computeType());
        }
        properties.put("publicNetworkAccess", settings.publicNetworkAccess());
        properties.put("networkRuleSet", networkRuleSet(settings.networkRuleSet()));
        properties.put("privateEndpointConnections", List.of());
        properties.put("sharedPrivateLinkResources", List.of());
        properties.put("eTag", eTag(service));
        Map<String, Object> encryption = new LinkedHashMap<>();
        encryption.put("enforcement", settings.cmkEnforcement());
        encryption.put("encryptionComplianceStatus", "Compliant");
        properties.put("encryptionWithCmk", encryption);
        properties.put("disableLocalAuth", settings.disableLocalAuth());
        properties.put("authOptions", authOptions(settings.authOptions()));
        properties.put("dataExfiltrationProtections", settings.dataExfiltrationProtections());
        properties.put("semanticSearch", settings.semanticSearch());
        if (settings.knowledgeRetrieval() != null) {
            properties.put("knowledgeRetrieval", settings.knowledgeRetrieval());
        }
        properties.put("upgradeAvailable", "notAvailable");
        properties.put("serviceUpgradedAt", null);
        return properties;
    }

    /** {@code {"apiKeyOnly": {}}} or {@code {"aadOrApiKey": {...}}}; null when local auth is disabled. */
    static Map<String, Object> authOptions(AuthOptions options) {
        if (options == null) {
            return null;
        }
        if (!options.aadOrApiKey()) {
            return Map.of("apiKeyOnly", Map.of());
        }
        Map<String, Object> aadOrApiKey = new LinkedHashMap<>();
        if (options.aadAuthFailureMode() != null) {
            aadOrApiKey.put("aadAuthFailureMode", options.aadAuthFailureMode());
        }
        return Map.of("aadOrApiKey", aadOrApiKey);
    }

    static Map<String, Object> networkRuleSet(NetworkRules rules) {
        List<Map<String, Object>> ipRules = new ArrayList<>();
        for (String value : rules.ipRules()) {
            ipRules.add(Map.of("value", value));
        }
        Map<String, Object> networkRuleSet = new LinkedHashMap<>();
        networkRuleSet.put("ipRules", ipRules);
        networkRuleSet.put("bypass", rules.bypass());
        return networkRuleSet;
    }

    /** The {@code identity} block, or null for a service without one, which Azure answers with no block at all. */
    static Map<String, Object> identity(ServiceIdentity identity) {
        if (identity == null) {
            return null;
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", identity.type());
        if (identity.principalId() != null) {
            block.put("principalId", identity.principalId());
            block.put("tenantId", identity.tenantId());
        }
        if (!identity.userAssignedIdentities().isEmpty()) {
            Map<String, Object> users = new LinkedHashMap<>();
            for (Map.Entry<String, UserIdentity> user : identity.userAssignedIdentities().entrySet()) {
                Map<String, Object> ids = new LinkedHashMap<>();
                ids.put("principalId", user.getValue().principalId());
                ids.put("clientId", user.getValue().clientId());
                users.put(user.getKey(), ids);
            }
            block.put("userAssignedIdentities", users);
        }
        return block;
    }

    /** The service in the generic resource listings: id, name, type, location and tags. */
    static Map<String, Object> indexEntry(SearchServiceRecord service) {
        Map<String, String> tags = new LinkedHashMap<>();
        service.tags().forEach((key, value) -> tags.put(key, String.valueOf(value)));
        return ArmResources.indexEntry(service.id(), service.name(), TYPE, service.location(), tags);
    }

    /**
     * The weak ETag of the service's last write, in the form Azure's answers carry it: the write time, URL-encoded,
     * as {@code W/"datetime'...'"}.
     */
    static String eTag(SearchServiceRecord service) {
        String time = ETAG_TIME.format(service.audit().lastModifiedAt());
        return "W/\"datetime'" + URLEncoder.encode(time, StandardCharsets.UTF_8) + "'\"";
    }

    private static Map<String, Object> systemData(Audit audit) {
        Map<String, Object> systemData = new LinkedHashMap<>();
        if (audit.createdBy() != null) {
            systemData.put("createdBy", audit.createdBy());
            systemData.put("createdByType", audit.createdByType());
        }
        systemData.put("createdAt", timestamp(audit.createdAt()));
        if (audit.lastModifiedBy() != null) {
            systemData.put("lastModifiedBy", audit.lastModifiedBy());
            systemData.put("lastModifiedByType", audit.lastModifiedByType());
        }
        systemData.put("lastModifiedAt", timestamp(audit.lastModifiedAt()));
        return systemData;
    }

    private static String timestamp(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
