package io.floci.az.services.search.arm;

import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.services.search.arm.SearchArmModels.Audit;
import io.floci.az.services.search.arm.SearchArmModels.AuthOptions;
import io.floci.az.services.search.arm.SearchArmModels.NetworkRules;
import io.floci.az.services.search.arm.SearchArmModels.QueryKey;
import io.floci.az.services.search.arm.SearchArmModels.SearchServiceRecord;
import io.floci.az.services.search.arm.SearchArmModels.ServiceIdentity;
import io.floci.az.services.search.arm.SearchArmModels.ServiceSettings;
import io.floci.az.services.search.arm.SearchArmModels.UserIdentity;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The search service a {@code Services_CreateOrUpdate} (PUT) or {@code Services_Update} (PATCH) body leaves behind,
 * checked against the rules the spec and Learn document.
 *
 * <p>A PUT replaces every property it omits with its default, as Learn's semantic ranker page describes it; a PATCH
 * changes only the properties it names. Both keep a service's keys, and its {@code location}, which is set on create
 * only. A PUT that omits {@code identity} keeps the stored one, and an explicit {@code None} or {@code null} removes
 * it: no public source says what Azure does with an omitted identity, and keeping it is the answer that never drops
 * an identity a client did not mean to remove. Read-only properties a client echoes from a GET ({@code endpoint},
 * {@code status}, {@code upgradeAvailable} and the like) are ignored.
 *
 * <p>Enum values are matched ignoring case and stored in the spelling Azure answers with: {@code hostingMode} in camel
 * case ({@code default}, {@code highDensity}) and {@code publicNetworkAccess} in Pascal case ({@code Enabled}), as the
 * service answers them whatever the request sent.
 *
 * <p>Azure's codes for a refused value are not public, so a value outside the rules is a {@code 400 BadRequest} with a
 * message naming the rule, and a value of the wrong JSON type is ARM's {@code 400 InvalidRequestContent}.
 */
final class SearchServiceWrite {

    /** The SKUs of the 2026-11-01 spec, in its spelling. */
    private static final List<String> SKUS = List.of("free", "basic", "standard", "standard2", "standard3",
        "storage_optimized_l1", "storage_optimized_l2", "serverless");

    private static final String FREE = "free";
    private static final String BASIC = "basic";
    private static final String STANDARD3 = "standard3";
    private static final String SERVERLESS = "serverless";

    /** The tiers a service can move between in place (Learn: "Change your pricing tier"). */
    private static final Set<String> CHANGEABLE_TIERS = Set.of(BASIC, "standard", "standard2", STANDARD3);

    private static final String DEFAULT_HOSTING_MODE = "default";
    private static final String HIGH_DENSITY = "highDensity";

    private static final Set<Integer> PARTITION_COUNTS = Set.of(1, 2, 3, 4, 6, 12);
    private static final int MAX_REPLICAS = 12;
    /** The most search units (replicas times partitions) a Standard or Storage Optimized service has. */
    private static final int MAX_SEARCH_UNITS = 36;
    /** The most replicas, and partitions, a Basic service has. */
    private static final int MAX_BASIC_SCALE = 3;
    /** The most partitions an S3 service in high-density hosting mode has. */
    private static final int MAX_HIGH_DENSITY_PARTITIONS = 3;

    private static final List<String> HOSTING_MODES = List.of(DEFAULT_HOSTING_MODE, HIGH_DENSITY);
    private static final List<String> PUBLIC_NETWORK_ACCESS = List.of("Enabled", "Disabled", "SecuredByPerimeter");
    private static final List<String> COMPUTE_TYPES = List.of("Default", "Confidential");
    private static final List<String> BYPASSES = List.of("None", "AzureServices", "AzurePortal");
    private static final List<String> CMK_ENFORCEMENTS = List.of("Disabled", "Enabled", "Unspecified");
    private static final List<String> FAILURE_MODES = List.of("http403", "http401WithBearerChallenge");
    private static final List<String> EXFILTRATION_PROTECTIONS = List.of("BlockAll");
    private static final List<String> SEMANTIC_PLANS = List.of("disabled", "free", "standard");
    private static final List<String> KNOWLEDGE_PLANS = List.of("free", "standard");

    private static final String SYSTEM_ASSIGNED = "SystemAssigned";
    private static final String USER_ASSIGNED = "UserAssigned";

    /** An IPv4 address, or an IPv4 range in CIDR notation. */
    private static final Pattern IPV4_RULE = Pattern.compile(
        "((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(/(3[0-2]|[12]?\\d))?");

    /** A write Azure would refuse, answered as an ARM error before anything is stored. */
    static final class RefusedWrite extends RuntimeException {

        private final int status;
        private final String code;

        RefusedWrite(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        Response response() {
            return ArmErrors.error(status, code, getMessage());
        }
    }

    /**
     * What a write needs besides its body.
     *
     * @param id              the service the request addresses
     * @param defaultLocation the location a create that names none gets
     * @param tenantId        the tenant of the service's subscription, which a system-assigned identity belongs to
     * @param userIdentities  the principal and client ids of a user-assigned identity, by its ARM id
     * @param now             the time of the write
     * @param caller          the principal the request's token names, or null
     * @param callerType      the type of that principal, or null
     */
    record Context(ArmResourceId id, String defaultLocation, String tenantId,
                   Function<String, UserIdentity> userIdentities, Instant now, String caller, String callerType) {
    }

    private SearchServiceWrite() {
    }

    /** The service a PUT leaves: a new one when {@code existing} is empty, else {@code existing} replaced. */
    static SearchServiceRecord put(Context context, Map<String, Object> body, Optional<SearchServiceRecord> existing) {
        Map<String, Object> properties = properties(body);
        String sku = sku(body).or(() -> existing.map(SearchServiceRecord::sku)).orElseThrow(() ->
            refused("The sku is required to create a search service."));
        ServiceSettings settings = settings(sku, defaults(sku), properties,
            existing.map(SearchServiceRecord::settings).orElse(null));
        ServiceIdentity identity = body.containsKey("identity")
            ? identity(context, body.get("identity"), existing.map(SearchServiceRecord::identity).orElse(null), false)
            : existing.map(SearchServiceRecord::identity).orElse(null);
        Map<String, Object> tags = tags(body.get("tags"));
        if (existing.isEmpty()) {
            check(sku, settings, identity);
            String location = ArmJson.string(body, "location", context.defaultLocation());
            return new SearchServiceRecord(context.id().id(), context.id().name(), location, tags, sku, identity,
                settings, SearchApiKeys.newKey(), SearchApiKeys.newKey(),
                List.of(new QueryKey(null, SearchApiKeys.newKey())),
                Audit.created(context.now(), context.caller(), context.callerType()));
        }
        return updated(context, existing.get(), sku, settings, identity, tags);
    }

    /** The service a PATCH leaves: {@code existing} with what the body names changed. */
    static SearchServiceRecord patch(Context context, Map<String, Object> body, SearchServiceRecord existing) {
        Map<String, Object> properties = properties(body);
        String sku = sku(body).orElse(existing.sku());
        if (SERVERLESS.equals(sku) != SERVERLESS.equals(existing.sku())) {
            throw tierChange(existing.sku(), sku);
        }
        ServiceSettings settings = settings(sku, existing.settings(), properties, existing.settings());
        ServiceIdentity identity = body.containsKey("identity")
            ? identity(context, body.get("identity"), existing.identity(), true)
            : existing.identity();
        Map<String, Object> tags = body.containsKey("tags") ? tags(body.get("tags")) : existing.tags();
        return updated(context, existing, sku, settings, identity, tags);
    }

    private static SearchServiceRecord updated(Context context, SearchServiceRecord existing, String sku,
                                               ServiceSettings settings, ServiceIdentity identity,
                                               Map<String, Object> tags) {
        if (!sku.equals(existing.sku()) && !(CHANGEABLE_TIERS.contains(existing.sku()) && CHANGEABLE_TIERS.contains(sku)
                && DEFAULT_HOSTING_MODE.equals(existing.settings().hostingMode()))) {
            throw tierChange(existing.sku(), sku);
        }
        if (!settings.hostingMode().equals(existing.settings().hostingMode())) {
            throw refused("The hosting mode of an existing search service cannot be changed from '"
                + existing.settings().hostingMode() + "' to '" + settings.hostingMode() + "'.");
        }
        check(sku, settings, identity);
        return new SearchServiceRecord(existing.id(), existing.name(), existing.location(), tags, sku, identity,
            settings, existing.primaryAdminKey(), existing.secondaryAdminKey(), existing.queryKeys(),
            existing.audit().modified(context.now(), context.caller(), context.callerType()));
    }

    private static RefusedWrite tierChange(String from, String to) {
        return refused("The pricing tier of a search service can change only between basic, standard, standard2 and "
            + "standard3, outside the high-density hosting mode; it cannot change from '" + from + "' to '" + to + "'.");
    }

    // ── Defaults and settings ───────────────────────────────────────────────

    /** The properties of a service whose write names none, per the spec's defaults. */
    static ServiceSettings defaults(String sku) {
        boolean serverless = SERVERLESS.equals(sku);
        return new ServiceSettings(serverless ? null : 1, serverless ? null : 1, DEFAULT_HOSTING_MODE,
            serverless ? null : "Default", "Enabled", new NetworkRules(List.of(), "None"), "Unspecified", false,
            AuthOptions.API_KEY_ONLY, List.of(), "free", null);
    }

    /**
     * {@code base} with every property {@code properties} names applied: a value replaces the base one, and an
     * explicit {@code null} resets it to the default. {@code stored} is the service's current settings, or null for a
     * create.
     *
     * <p>{@code disableLocalAuth} cannot be true while {@code authOptions} are defined (spec). A write that disables
     * local auth and names authentication options is refused, unless they are the ones stored: that is the read,
     * modify and write of a GET answer, which is how {@code az search service update --disable-local-auth} disables
     * keys, as Learn documents it. Disabling local auth drops the options.
     */
    private static ServiceSettings settings(String sku, ServiceSettings base, Map<String, Object> properties,
                                            ServiceSettings stored) {
        ServiceSettings defaults = defaults(sku);
        boolean serverless = SERVERLESS.equals(sku);
        Integer replicas = scale(properties, "replicaCount", base.replicaCount(), defaults.replicaCount(), serverless);
        Integer partitions = scale(properties, "partitionCount", base.partitionCount(), defaults.partitionCount(),
            serverless);
        String hostingMode = choice(properties, "hostingMode", HOSTING_MODES, base.hostingMode(),
            defaults.hostingMode());
        String computeType = serverless ? null
            : choice(properties, "computeType", COMPUTE_TYPES, base.computeType(), defaults.computeType());
        String publicNetworkAccess = choice(properties, "publicNetworkAccess", PUBLIC_NETWORK_ACCESS,
            base.publicNetworkAccess(), defaults.publicNetworkAccess());
        NetworkRules networkRules = networkRules(properties, base.networkRuleSet(), defaults.networkRuleSet());
        String cmkEnforcement = cmkEnforcement(properties, base.cmkEnforcement(), defaults.cmkEnforcement());
        List<String> exfiltration = exfiltrationProtections(properties, base.dataExfiltrationProtections());
        String semanticSearch = choice(properties, "semanticSearch", SEMANTIC_PLANS, base.semanticSearch(),
            defaults.semanticSearch());
        String knowledgeRetrieval = choice(properties, "knowledgeRetrieval", KNOWLEDGE_PLANS,
            base.knowledgeRetrieval(), defaults.knowledgeRetrieval());

        Boolean disableRequested = bool(properties, "disableLocalAuth");
        AuthOptions authRequested = authOptions(properties);
        if (Boolean.TRUE.equals(disableRequested) && authRequested != null
                && (stored == null || !authRequested.equals(stored.authOptions()))) {
            throw refused("disableLocalAuth cannot be true when authOptions is set: a service that refuses API keys "
                + "has no data plane authentication options.");
        }
        boolean disableLocalAuth = disableRequested != null ? disableRequested
            : authRequested == null && base.disableLocalAuth();
        AuthOptions authOptions = disableLocalAuth ? null
            : authRequested != null ? authRequested
            : base.authOptions() != null ? base.authOptions() : AuthOptions.API_KEY_ONLY;

        return new ServiceSettings(replicas, partitions, hostingMode, computeType, publicNetworkAccess, networkRules,
            cmkEnforcement, disableLocalAuth, authOptions, exfiltration, semanticSearch, knowledgeRetrieval);
    }

    /**
     * A replica or partition count. The serverless SKU has neither: it accepts a count of 1, which clients send by
     * default, and keeps none.
     */
    private static Integer scale(Map<String, Object> properties, String name, Integer base, Integer fallback,
                                 boolean serverless) {
        if (!properties.containsKey(name)) {
            return base;
        }
        Object value = properties.get(name);
        if (value == null) {
            return fallback;
        }
        long count = switch (value) {
            case Integer number -> number;
            case Long number -> number;
            default -> throw wrongType(name, "an integer");
        };
        if (serverless) {
            if (count != 1) {
                throw refused("The serverless SKU has no " + name + ": the service scales on its own.");
            }
            return null;
        }
        if (count < 1 || count > MAX_REPLICAS) {
            throw refused(name + " must be between 1 and " + MAX_REPLICAS + ", not " + count + ".");
        }
        return (int) count;
    }

    private static NetworkRules networkRules(Map<String, Object> properties, NetworkRules base, NetworkRules fallback) {
        if (!properties.containsKey("networkRuleSet")) {
            return base;
        }
        Object value = properties.get("networkRuleSet");
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw wrongType("networkRuleSet", "an object");
        }
        Map<String, Object> rules = ArmJson.cast(map);
        List<String> ipRules = rules.containsKey("ipRules") ? ipRules(rules.get("ipRules")) : base.ipRules();
        String bypass = choice(rules, "bypass", BYPASSES, base.bypass(), fallback.bypass());
        return new NetworkRules(ipRules, bypass);
    }

    private static List<String> ipRules(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> entries)) {
            throw wrongType("networkRuleSet.ipRules", "an array");
        }
        List<String> rules = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> rule) || !(rule.get("value") instanceof String address)) {
                throw wrongType("networkRuleSet.ipRules[]", "an object with a string value");
            }
            if (!IPV4_RULE.matcher(address).matches()) {
                throw refused("The IP rule '" + address + "' is not an IPv4 address or an IPv4 range in CIDR format.");
            }
            rules.add(address);
        }
        return rules;
    }

    private static String cmkEnforcement(Map<String, Object> properties, String base, String fallback) {
        if (!properties.containsKey("encryptionWithCmk")) {
            return base;
        }
        Object value = properties.get("encryptionWithCmk");
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw wrongType("encryptionWithCmk", "an object");
        }
        return choice(ArmJson.cast(map), "enforcement", CMK_ENFORCEMENTS, base, fallback);
    }

    private static List<String> exfiltrationProtections(Map<String, Object> properties, List<String> base) {
        if (!properties.containsKey("dataExfiltrationProtections")) {
            return base;
        }
        Object value = properties.get("dataExfiltrationProtections");
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> entries)) {
            throw wrongType("dataExfiltrationProtections", "an array");
        }
        Set<String> protections = new LinkedHashSet<>();
        for (Object entry : entries) {
            if (!(entry instanceof String text)) {
                throw wrongType("dataExfiltrationProtections[]", "a string");
            }
            protections.add(canonical("dataExfiltrationProtections", text, EXFILTRATION_PROTECTIONS));
        }
        return List.copyOf(protections);
    }

    /** The requested {@code authOptions}, or null when the write names none (or names it null). */
    private static AuthOptions authOptions(Map<String, Object> properties) {
        Object value = properties.get("authOptions");
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw wrongType("authOptions", "an object");
        }
        Map<String, Object> options = ArmJson.cast(map);
        boolean apiKeyOnly = options.get("apiKeyOnly") != null;
        Object aadOrApiKey = options.get("aadOrApiKey");
        if (apiKeyOnly == (aadOrApiKey != null)) {
            throw refused("authOptions must set exactly one of apiKeyOnly and aadOrApiKey.");
        }
        if (apiKeyOnly) {
            return AuthOptions.API_KEY_ONLY;
        }
        if (!(aadOrApiKey instanceof Map<?, ?> aad)) {
            throw wrongType("authOptions.aadOrApiKey", "an object");
        }
        return new AuthOptions(true, choice(ArmJson.cast(aad), "aadAuthFailureMode", FAILURE_MODES, null, null));
    }

    private static Boolean bool(Map<String, Object> properties, String name) {
        return switch (properties.get(name)) {
            case null -> null;
            case Boolean flag -> flag;
            default -> throw wrongType(name, "a boolean");
        };
    }

    /**
     * The value of {@code name} in {@code values}' spelling, matched ignoring case: {@code base} when the object
     * omits it, {@code fallback} when it is null.
     */
    private static String choice(Map<String, Object> object, String name, List<String> values, String base,
                                 String fallback) {
        if (!object.containsKey(name)) {
            return base;
        }
        Object value = object.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text)) {
            throw wrongType(name, "a string");
        }
        return canonical(name, text, values);
    }

    private static String canonical(String name, String value, List<String> values) {
        for (String candidate : values) {
            if (candidate.equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw refused("'" + value + "' is not a valid " + name + "; the valid values are " + String.join(", ", values)
            + ".");
    }

    // ── SKU and the tier rules ──────────────────────────────────────────────

    private static Optional<String> sku(Map<String, Object> body) {
        Object value = body.get("sku");
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof Map<?, ?> sku)) {
            throw wrongType("sku", "an object");
        }
        Object name = sku.get("name");
        if (name == null) {
            return Optional.empty();
        }
        if (!(name instanceof String text)) {
            throw wrongType("sku.name", "a string");
        }
        return Optional.of(canonical("sku.name", text, SKUS));
    }

    /**
     * The rules of the service's tier (Learn: "Service limits", "Feature availability by tier"): the replica and
     * partition limits of each tier, the 36 search units of a Standard or Storage Optimized service, high density on
     * S3 only, and the features the Free tier lacks.
     */
    private static void check(String sku, ServiceSettings settings, ServiceIdentity identity) {
        if (HIGH_DENSITY.equals(settings.hostingMode()) && !STANDARD3.equals(sku)) {
            throw refused("The hosting mode 'highDensity' is available on the standard3 SKU only, not on '" + sku
                + "'.");
        }
        if (!SERVERLESS.equals(sku)) {
            checkScale(sku, settings.replicaCount(), settings.partitionCount(), settings.hostingMode());
        }
        if (FREE.equals(sku)) {
            checkFreeTier(settings, identity);
        }
    }

    private static void checkScale(String sku, int replicas, int partitions, String hostingMode) {
        if (!PARTITION_COUNTS.contains(partitions)) {
            throw refused("partitionCount must be 1, 2, 3, 4, 6 or 12, not " + partitions + ".");
        }
        if (FREE.equals(sku)) {
            if (replicas != 1 || partitions != 1) {
                throw refused("A free search service has one replica and one partition and cannot be scaled.");
            }
            return;
        }
        if (BASIC.equals(sku)) {
            if (replicas > MAX_BASIC_SCALE || partitions > MAX_BASIC_SCALE) {
                throw refused("A basic search service has at most " + MAX_BASIC_SCALE + " replicas and "
                    + MAX_BASIC_SCALE + " partitions.");
            }
            return;
        }
        if (HIGH_DENSITY.equals(hostingMode) && partitions > MAX_HIGH_DENSITY_PARTITIONS) {
            throw refused("A standard3 search service in the highDensity hosting mode has at most "
                + MAX_HIGH_DENSITY_PARTITIONS + " partitions.");
        }
        if (replicas * partitions > MAX_SEARCH_UNITS) {
            throw refused(replicas + " replicas and " + partitions + " partitions are " + replicas * partitions
                + " search units; a search service has at most " + MAX_SEARCH_UNITS + ".");
        }
    }

    private static void checkFreeTier(ServiceSettings settings, ServiceIdentity identity) {
        if (!settings.networkRuleSet().ipRules().isEmpty()) {
            throw refused("IP firewall rules are not available on the free tier.");
        }
        if (identity != null) {
            throw refused("Managed identities are not available on the free tier.");
        }
        if ("Enabled".equals(settings.cmkEnforcement())) {
            throw refused("Customer-managed key encryption is not available on the free tier.");
        }
        if ("standard".equals(settings.semanticSearch())) {
            throw refused("The standard semantic ranker plan requires the basic tier or higher.");
        }
    }

    // ── Identity ────────────────────────────────────────────────────────────

    /**
     * The identity an {@code identity} value leaves: none for {@code null} or type {@code None}. A system-assigned
     * identity keeps its principal while the service keeps one, and gets a new one when it is turned on again, as
     * Azure assigns them. A PATCH merges {@code userAssignedIdentities} into the stored ones, a {@code null} entry
     * removing one; a PUT names them all.
     */
    private static ServiceIdentity identity(Context context, Object value, ServiceIdentity existing, boolean merge) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw wrongType("identity", "an object");
        }
        Map<String, Object> requested = ArmJson.cast(map);
        if (!(requested.get("type") instanceof String type)) {
            throw refused("identity.type is required: None, SystemAssigned, UserAssigned or "
                + "'SystemAssigned, UserAssigned'.");
        }
        Set<String> kinds = identityKinds(type);
        if (kinds.isEmpty()) {
            return null;
        }
        String principalId = null;
        String tenantId = null;
        if (kinds.contains(SYSTEM_ASSIGNED)) {
            boolean kept = existing != null && existing.principalId() != null;
            principalId = kept ? existing.principalId() : UUID.randomUUID().toString();
            tenantId = kept ? existing.tenantId() : context.tenantId();
        }
        Map<String, UserIdentity> users = new LinkedHashMap<>();
        if (kinds.contains(USER_ASSIGNED)) {
            if (merge && existing != null) {
                users.putAll(existing.userAssignedIdentities());
            }
            Object named = requested.get("userAssignedIdentities");
            if (named != null && !(named instanceof Map<?, ?>)) {
                throw wrongType("identity.userAssignedIdentities", "an object");
            }
            for (Map.Entry<String, Object> entry : ArmJson.cast(named).entrySet()) {
                users.keySet().removeIf(key -> key.equalsIgnoreCase(entry.getKey()));
                if (entry.getValue() != null) {
                    users.put(entry.getKey(), context.userIdentities().apply(entry.getKey()));
                }
            }
        }
        return new ServiceIdentity(String.join(", ", kinds), principalId, tenantId, users);
    }

    /** The kinds an identity type names, in the order Azure spells them; empty for {@code None}. */
    private static Set<String> identityKinds(String type) {
        Set<String> kinds = new LinkedHashSet<>();
        if ("None".equalsIgnoreCase(type.strip())) {
            return kinds;
        }
        for (String part : type.split(",")) {
            String kind = part.strip();
            if (SYSTEM_ASSIGNED.equalsIgnoreCase(kind)) {
                kinds.add(SYSTEM_ASSIGNED);
            } else if (USER_ASSIGNED.equalsIgnoreCase(kind)) {
                kinds.add(USER_ASSIGNED);
            } else {
                throw refused("'" + type + "' is not a valid identity.type; the valid values are None, SystemAssigned, "
                    + "UserAssigned and 'SystemAssigned, UserAssigned'.");
            }
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String kind : List.of(SYSTEM_ASSIGNED, USER_ASSIGNED)) {
            if (kinds.contains(kind)) {
                ordered.add(kind);
            }
        }
        return ordered;
    }

    // ── Body helpers ────────────────────────────────────────────────────────

    private static Map<String, Object> properties(Map<String, Object> body) {
        Object value = body.get("properties");
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw wrongType("properties", "an object");
        }
        return ArmJson.cast(map);
    }

    private static Map<String, Object> tags(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw wrongType("tags", "an object");
        }
        return ArmJson.cast(map);
    }

    static RefusedWrite refused(String message) {
        return new RefusedWrite(400, "BadRequest", message);
    }

    /** ARM's answer for a body value of the wrong JSON type: {@code name} must be {@code expected}. */
    static RefusedWrite wrongType(String name, String expected) {
        return new RefusedWrite(400, "InvalidRequestContent", "The request content was invalid and could not be "
            + "deserialized: " + name + " must be " + expected + ".");
    }
}
