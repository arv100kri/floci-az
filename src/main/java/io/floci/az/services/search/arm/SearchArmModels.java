package io.floci.az.services.search.arm;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The persisted Microsoft.Search resources. */
public final class SearchArmModels {

    private SearchArmModels() {
    }

    /**
     * A search service as the store keeps it: what its writes set, its API keys, and who wrote it when.
     *
     * @param id                the ARM id, in the casing of the request that created it
     * @param name              the service name, in the casing of the request that created it
     * @param location          the location the create named
     * @param tags              the tags the last write set, echoed as they came
     * @param sku               the SKU name, such as {@code basic}
     * @param identity          the managed identity, or null for none
     * @param settings          the {@code properties} a write sets
     * @param primaryAdminKey   the primary admin API key
     * @param secondaryAdminKey the secondary admin API key
     * @param queryKeys         the query API keys, oldest first
     * @param audit             who created and last changed the service, and when
     */
    @RegisterForReflection
    public record SearchServiceRecord(String id, String name, String location, Map<String, Object> tags, String sku,
                                      ServiceIdentity identity, ServiceSettings settings, String primaryAdminKey,
                                      String secondaryAdminKey, List<QueryKey> queryKeys, Audit audit) {

        public SearchServiceRecord {
            tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
            queryKeys = queryKeys == null ? List.of() : List.copyOf(queryKeys);
        }

        /** This service with other admin keys; key changes leave the audit and the ETag as they were. */
        public SearchServiceRecord withAdminKeys(String primary, String secondary) {
            return new SearchServiceRecord(id, name, location, tags, sku, identity, settings, primary, secondary,
                queryKeys, audit);
        }

        /** This service with other query keys. */
        public SearchServiceRecord withQueryKeys(List<QueryKey> keys) {
            return new SearchServiceRecord(id, name, location, tags, sku, identity, settings, primaryAdminKey,
                secondaryAdminKey, keys, audit);
        }
    }

    /**
     * The {@code properties} of a search service a write sets, in the spelling responses carry.
     *
     * @param replicaCount                the replicas; null for the serverless SKU, which has none
     * @param partitionCount              the partitions; null for the serverless SKU, which has none
     * @param hostingMode                 {@code default} or {@code highDensity}
     * @param computeType                 {@code Default} or {@code Confidential}; null for the serverless SKU
     * @param publicNetworkAccess         {@code Enabled}, {@code Disabled} or {@code SecuredByPerimeter}
     * @param networkRuleSet              the IP rules and the bypass
     * @param cmkEnforcement              {@code encryptionWithCmk.enforcement}
     * @param disableLocalAuth            whether API keys are refused
     * @param authOptions                 the data plane's authentication options; null when local auth is disabled
     * @param dataExfiltrationProtections the blocked exfiltration scenarios
     * @param semanticSearch              the semantic ranker plan
     * @param knowledgeRetrieval          the agentic retrieval plan, or null when no write set one
     */
    @RegisterForReflection
    public record ServiceSettings(Integer replicaCount, Integer partitionCount, String hostingMode, String computeType,
                                  String publicNetworkAccess, NetworkRules networkRuleSet, String cmkEnforcement,
                                  boolean disableLocalAuth, AuthOptions authOptions,
                                  List<String> dataExfiltrationProtections, String semanticSearch,
                                  String knowledgeRetrieval) {

        public ServiceSettings {
            dataExfiltrationProtections = dataExfiltrationProtections == null
                ? List.of() : List.copyOf(dataExfiltrationProtections);
        }
    }

    /**
     * The {@code networkRuleSet} of a search service.
     *
     * @param ipRules the allowed IPv4 addresses and CIDR ranges, as written
     * @param bypass  the origins that bypass the rules: {@code None}, {@code AzureServices} or {@code AzurePortal}
     */
    @RegisterForReflection
    public record NetworkRules(List<String> ipRules, String bypass) {

        public NetworkRules {
            ipRules = ipRules == null ? List.of() : List.copyOf(ipRules);
        }
    }

    /**
     * The {@code authOptions} of a search service: {@code apiKeyOnly}, or {@code aadOrApiKey} with its failure mode.
     *
     * @param aadOrApiKey        whether Microsoft Entra tokens are accepted beside API keys
     * @param aadAuthFailureMode {@code http403} or {@code http401WithBearerChallenge}; null when unset
     */
    @RegisterForReflection
    public record AuthOptions(boolean aadOrApiKey, String aadAuthFailureMode) {

        /** {@code {"apiKeyOnly": {}}}: the default. */
        public static final AuthOptions API_KEY_ONLY = new AuthOptions(false, null);
    }

    /**
     * The managed identity of a search service.
     *
     * @param type                   {@code SystemAssigned}, {@code UserAssigned} or {@code SystemAssigned, UserAssigned}
     * @param principalId            the system-assigned identity's principal, or null without one
     * @param tenantId               the system-assigned identity's tenant, or null without one
     * @param userAssignedIdentities the user-assigned identities by ARM id, in the casing the write named them
     */
    @RegisterForReflection
    public record ServiceIdentity(String type, String principalId, String tenantId,
                                  Map<String, UserIdentity> userAssignedIdentities) {

        public ServiceIdentity {
            userAssignedIdentities = userAssignedIdentities == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(userAssignedIdentities));
        }
    }

    /** A user-assigned identity of a search service: its principal and client ids. */
    @RegisterForReflection
    public record UserIdentity(String principalId, String clientId) {
    }

    /**
     * A query API key.
     *
     * @param name the name it was created with; null for the key the service was created with, which has none
     * @param key  the key
     */
    @RegisterForReflection
    public record QueryKey(String name, String key) {
    }

    /**
     * Who created and last changed a resource, and when, as {@code systemData} reports it.
     *
     * @param createdBy          the creating principal, or null when the request carried no token
     * @param createdByType      {@code User}, {@code Application} or {@code ManagedIdentity}, or null
     * @param lastModifiedBy     the principal of the last change, or null when the request carried no token
     * @param lastModifiedByType the type of that principal, or null
     */
    @RegisterForReflection
    public record Audit(Instant createdAt, String createdBy, String createdByType, Instant lastModifiedAt,
                        String lastModifiedBy, String lastModifiedByType) {

        /** A resource created at {@code at} by {@code by}, of {@code byType}; both null for an unknown caller. */
        public static Audit created(Instant at, String by, String byType) {
            return new Audit(at, by, byType, at, by, byType);
        }

        /** This audit after a change at {@code at} by {@code by}, of {@code byType}. */
        public Audit modified(Instant at, String by, String byType) {
            return new Audit(createdAt, createdBy, createdByType, at, by, byType);
        }
    }
}
