package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Custom Web API skill's secrets, as documented under "URI redaction and credential-preserving updates": GET and
 * LIST answer {@code <redacted>} for the values of the {@code sig} and {@code code} query parameters of {@code uri}
 * (names matched ignoring case and percent-encoding, repeated parameters by occurrence) and for every
 * {@code httpHeaders} value. An update of the same skill may send those placeholders, or {@code <unchanged>}, to keep
 * the stored values: a whole {@code uri} of {@code <unchanged>} keeps the stored URI; a placeholder query value keeps the
 * stored value when the endpoint is the same (for {@code sig}, only when the rest of the query is too); a placeholder
 * header keeps the stored header of the same skill and endpoint. A placeholder that cannot be resolved that way is a
 * 400 asking for the actual value, and nothing is applied.
 */
final class WebApiSecrets {

    static final String REDACTED = "<redacted>";
    static final String UNCHANGED = "<unchanged>";
    static final String WEB_API_SKILL = "#Microsoft.Skills.Custom.WebApiSkill";

    private static final Set<String> SECRET_PARAMETERS = Set.of("sig", "code");

    private WebApiSecrets() {
    }

    /** {@code uri} with the value of every {@code sig} and {@code code} query parameter replaced by the placeholder. */
    static String redactUri(String uri) {
        if (uri == null) {
            return null;
        }
        ParsedUri parsed = ParsedUri.parse(uri);
        if (parsed.parameters().isEmpty()) {
            return uri;
        }
        List<Parameter> redacted = new ArrayList<>();
        for (Parameter parameter : parsed.parameters()) {
            redacted.add(parameter.isSecret() && parameter.value() != null ? parameter.withValue(REDACTED) : parameter);
        }
        return parsed.withParameters(redacted).toString();
    }

    /** Redacts the Web API skills of {@code skillset}, a representation about to be answered, in place. */
    static void redact(ObjectNode skillset) {
        for (ObjectNode skill : webApiSkills(skillset)) {
            JsonNode uri = skill.get("uri");
            if (uri != null && uri.isTextual()) {
                skill.put("uri", redactUri(uri.textValue()));
            }
            if (skill.get("httpHeaders") instanceof ObjectNode headers) {
                List<String> headerNames = new ArrayList<>();
                headers.fieldNames().forEachRemaining(headerNames::add);
                for (String name : headerNames) {
                    headers.put(name, REDACTED);
                }
            }
        }
    }

    /**
     * Replaces the placeholders in the Web API skills of {@code request} with the values {@code stored} keeps for the
     * same skill and endpoint.
     *
     * @param request the definition being written; modified in place
     * @param stored  the stored definition it replaces, or null when it creates one
     * @throws SearchException a 400 when a placeholder has no single stored value to stand for
     */
    static void restore(ObjectNode request, ObjectNode stored) {
        JsonNode skills = request.get("skills");
        if (!(skills instanceof ArrayNode array)) {
            return;
        }
        Map<String, ObjectNode> storedSkills = new HashMap<>();
        for (ObjectNode skill : webApiSkills(stored)) {
            String name = SkillDefinition.text(skill, "name");
            if (name != null) {
                storedSkills.put(name, skill);
            }
        }
        for (int index = 0; index < array.size(); index++) {
            if (array.get(index) instanceof ObjectNode skill
                    && WEB_API_SKILL.equals(SkillDefinition.text(skill, "@odata.type"))) {
                String name = SkillDefinition.text(skill, "name");
                String resolvedName = name == null || name.isEmpty() ? "#" + (index + 1) : name;
                restoreSkill(skill, resolvedName, storedSkills.get(resolvedName));
            }
        }
    }

    private static void restoreSkill(ObjectNode skill, String name, ObjectNode stored) {
        String storedUri = stored == null ? null : SkillDefinition.text(stored, "uri");
        String uri = SkillDefinition.text(skill, "uri");
        if (uri != null && uri.trim().equalsIgnoreCase(UNCHANGED)) {
            if (storedUri == null) {
                throw unresolved(name, "'uri'");
            }
            uri = storedUri;
            skill.put("uri", uri);
        } else if (uri != null) {
            ParsedUri parsed = ParsedUri.parse(uri);
            if (parsed.hasSecretPlaceholder()) {
                uri = restoreUri(name, parsed, storedUri);
                skill.put("uri", uri);
            }
        }
        if (skill.get("httpHeaders") instanceof ObjectNode headers) {
            restoreHeaders(name, headers, uri, stored, storedUri);
        }
    }

    private static String restoreUri(String name, ParsedUri parsed, String storedUri) {
        if (storedUri == null) {
            throw unresolved(name, "a secret query parameter of 'uri'");
        }
        ParsedUri previous = ParsedUri.parse(storedUri);
        if (!previous.base().equals(parsed.base())) {
            throw unresolved(name, "a secret query parameter of 'uri', whose endpoint changed");
        }
        Map<String, Integer> seen = new HashMap<>();
        List<Parameter> restored = new ArrayList<>();
        for (Parameter parameter : parsed.parameters()) {
            if (!parameter.isSecret()) {
                restored.add(parameter);
                continue;
            }
            int occurrence = seen.merge(parameter.normalizedName(), 1, Integer::sum) - 1;
            if (!parameter.isPlaceholder()) {
                restored.add(parameter);
                continue;
            }
            Parameter original = previous.occurrence(parameter.normalizedName(), occurrence);
            if (original == null || original.value() == null) {
                throw unresolved(name, "the query parameter '" + parameter.name() + "' of 'uri'");
            }
            if (parameter.normalizedName().equals("sig") && !parsed.nonSecretQuery().equals(previous.nonSecretQuery())) {
                throw unresolved(name, "the 'sig' query parameter of 'uri', whose other query parameters changed");
            }
            restored.add(parameter.withValue(original.value()));
        }
        return parsed.withParameters(restored).toString();
    }

    private static void restoreHeaders(String name, ObjectNode headers, String uri, ObjectNode stored,
                                       String storedUri) {
        List<String> headerNames = new ArrayList<>();
        headers.fieldNames().forEachRemaining(headerNames::add);
        for (String header : headerNames) {
            JsonNode value = headers.get(header);
            if (value == null || !value.isTextual() || !isPlaceholder(value.textValue())) {
                continue;
            }
            if (stored == null || storedUri == null || uri == null
                    || !ParsedUri.parse(storedUri).base().equals(ParsedUri.parse(uri).base())) {
                throw unresolved(name, "the header '" + header + "'");
            }
            String original = storedHeader(stored, header);
            if (original == null) {
                throw unresolved(name, "the header '" + header + "'");
            }
            headers.put(header, original);
        }
    }

    private static String storedHeader(ObjectNode stored, String header) {
        if (!(stored.get("httpHeaders") instanceof ObjectNode headers)) {
            return null;
        }
        Iterator<Map.Entry<String, JsonNode>> entries = headers.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (entry.getKey().equalsIgnoreCase(header) && entry.getValue().isTextual()) {
                return entry.getValue().textValue();
            }
        }
        return null;
    }

    private static List<ObjectNode> webApiSkills(ObjectNode skillset) {
        List<ObjectNode> found = new ArrayList<>();
        if (skillset != null && skillset.get("skills") instanceof ArrayNode skills) {
            for (JsonNode skill : skills) {
                if (skill instanceof ObjectNode object && WEB_API_SKILL.equals(SkillDefinition.text(object,
                        "@odata.type"))) {
                    found.add(object);
                }
            }
        }
        return found;
    }

    static boolean isPlaceholder(String value) {
        if (value == null) {
            return false;
        }
        String decoded = decode(value).trim();
        return decoded.equalsIgnoreCase(REDACTED) || decoded.equalsIgnoreCase(UNCHANGED);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static SearchException unresolved(String skill, String what) {
        return SearchException.badRequest("The request is invalid. Details: skill '" + skill + "' uses a '"
            + REDACTED + "' or '" + UNCHANGED + "' placeholder for " + what + ", but no single stored value of the "
            + "same skill and endpoint can be kept. Provide the actual secret value.");
    }

    /**
     * One query parameter, as written.
     *
     * @param name  the name, as written
     * @param value the value, as written; null for a parameter without {@code =}
     */
    private record Parameter(String name, String value) {

        String normalizedName() {
            return decode(name).toLowerCase(Locale.ROOT);
        }

        boolean isSecret() {
            return SECRET_PARAMETERS.contains(normalizedName());
        }

        boolean isPlaceholder() {
            return WebApiSecrets.isPlaceholder(value);
        }

        Parameter withValue(String newValue) {
            return new Parameter(name, newValue);
        }

        @Override
        public String toString() {
            return value == null ? name : name + "=" + value;
        }
    }

    /**
     * A URI split into what precedes the query, the query parameters and the fragment, each kept as written.
     *
     * @param base       everything before {@code ?}
     * @param parameters the query parameters in order
     * @param fragment   the fragment with its {@code #}, or empty
     */
    private record ParsedUri(String base, List<Parameter> parameters, String fragment) {

        static ParsedUri parse(String uri) {
            String fragment = "";
            String rest = uri;
            int hash = rest.indexOf('#');
            if (hash >= 0) {
                fragment = rest.substring(hash);
                rest = rest.substring(0, hash);
            }
            int question = rest.indexOf('?');
            if (question < 0) {
                return new ParsedUri(rest, List.of(), fragment);
            }
            List<Parameter> parameters = new ArrayList<>();
            for (String pair : rest.substring(question + 1).split("&", -1)) {
                if (pair.isEmpty()) {
                    continue;
                }
                int equals = pair.indexOf('=');
                parameters.add(equals < 0 ? new Parameter(pair, null)
                    : new Parameter(pair.substring(0, equals), pair.substring(equals + 1)));
            }
            return new ParsedUri(rest.substring(0, question), List.copyOf(parameters), fragment);
        }

        boolean hasSecretPlaceholder() {
            for (Parameter parameter : parameters) {
                if (parameter.isSecret() && parameter.isPlaceholder()) {
                    return true;
                }
            }
            return false;
        }

        Parameter occurrence(String normalizedName, int occurrence) {
            int seen = 0;
            for (Parameter parameter : parameters) {
                if (parameter.normalizedName().equals(normalizedName)) {
                    if (seen == occurrence) {
                        return parameter;
                    }
                    seen++;
                }
            }
            return null;
        }

        /** The query with every secret value blanked: what a {@code sig} is bound to. */
        String nonSecretQuery() {
            StringBuilder query = new StringBuilder();
            for (Parameter parameter : parameters) {
                query.append(parameter.isSecret() ? parameter.name() + "=" : parameter.toString()).append('&');
            }
            return query.toString();
        }

        ParsedUri withParameters(List<Parameter> replaced) {
            return new ParsedUri(base, List.copyOf(replaced), fragment);
        }

        @Override
        public String toString() {
            if (parameters.isEmpty()) {
                return base + fragment;
            }
            StringBuilder uri = new StringBuilder(base).append('?');
            for (int index = 0; index < parameters.size(); index++) {
                if (index > 0) {
                    uri.append('&');
                }
                uri.append(parameters.get(index));
            }
            return uri.append(fragment).toString();
        }
    }
}
