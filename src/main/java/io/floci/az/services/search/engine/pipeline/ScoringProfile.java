package io.floci.az.services.search.engine.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.services.search.engine.analysis.LexicalNormalizer;
import io.floci.az.services.search.engine.index.DocumentValues;
import io.floci.az.services.search.engine.index.GeoPoint;
import io.floci.az.services.search.engine.index.IndexedDocument;
import io.floci.az.services.search.engine.index.LiveIndex;
import io.floci.az.services.search.model.ScoringProfileValidator;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchJson;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A scoring profile of the index, compiled for one query with the request's {@code scoringParameters}, per Learn's
 * "Add scoring profiles" and the Create Index reference.
 *
 * <p>Text weights multiply a field's BM25 score. Each function yields, for a document whose value lies in its range,
 * an interpolated position {@code v} between 0 (the start of the range) and 1 (the most boosted end): {@code linear}
 * {@code v = t}, {@code constant} {@code v = 1}, {@code quadratic} {@code v = 1 - (1 - t)^2} (slow then fast decay),
 * {@code logarithmic} {@code v = 1 - log10(1 + 9 (1 - t))} (fast then slow decay), where t is the document's relative
 * position in the range. A function's boost is a multiplier, so its contribution is {@code (boost - 1) * v}, 0 out of
 * range; {@code functionAggregation} combines the contributions (sum by default, average, minimum, maximum,
 * firstMatching, or product of the multipliers) and the score is multiplied by {@code 1 + combined}. Ranges:
 * magnitude from {@code boostingRangeStart} to {@code boostingRangeEnd} (either may be the larger one; values past the
 * end keep the full boost with {@code constantBoostBeyondRange}); freshness from now back {@code boostingDuration}
 * (forward when the duration is negative); distance within {@code boostingDistance} km of the reference point; tag
 * when any value of the field equals one of the tags.</p>
 */
final class ScoringProfile {

    private final String name;
    private final Map<String, Double> textWeights;
    private final List<Function> functions;
    private final String aggregation;

    private ScoringProfile(String name, Map<String, Double> textWeights, List<Function> functions,
                           String aggregation) {
        this.name = name;
        this.textWeights = textWeights;
        this.functions = functions;
        this.aggregation = aggregation;
    }

    /**
     * The profile a query uses: its {@code scoringProfile}, else the index's {@code defaultScoringProfile}; null when
     * neither names one. An unknown profile, or a missing or malformed parameter it needs, is a 400.
     */
    static ScoringProfile resolve(SearchExecution execution) {
        String requested = execution.request().scoringProfile();
        LiveIndex index = execution.index();
        String profileName = requested == null || requested.isBlank()
            ? SearchJson.text(index.definition().json(), "defaultScoringProfile") : requested;
        if (profileName == null) {
            return null;
        }
        for (JsonNode profile : index.definition().json().path("scoringProfiles")) {
            if (profileName.equals(SearchJson.text(profile, "name"))) {
                return compile(profile, index, ScoringParameters.parse(execution.request().scoringParameters()));
            }
        }
        throw SearchException.badRequest("Unknown scoring profile '" + profileName + "'.\r\nParameter name: "
            + "scoringProfile");
    }

    private static ScoringProfile compile(JsonNode profile, LiveIndex index, Map<String, List<String>> parameters) {
        String name = SearchJson.text(profile, "name");
        Map<String, Double> weights = new LinkedHashMap<>();
        JsonNode weightNode = profile.path("text").path("weights");
        Iterator<Map.Entry<String, JsonNode>> entries = weightNode.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            weights.put(entry.getKey(), entry.getValue().doubleValue());
        }
        List<Function> functions = new ArrayList<>();
        for (JsonNode function : profile.path("functions")) {
            functions.add(function(name, function, index, parameters));
        }
        String aggregation = SearchJson.text(profile, "functionAggregation");
        return new ScoringProfile(name, weights, functions,
            aggregation == null ? "sum" : aggregation.toLowerCase(Locale.ROOT));
    }

    private static Function function(String profile, JsonNode function, LiveIndex index,
                                     Map<String, List<String>> parameters) {
        String type = SearchJson.text(function, "type");
        SearchFieldDefinition field = index.definition().field(SearchJson.text(function, "fieldName")).orElseThrow();
        List<String> path = List.of(field.path().split("/"));
        double boost = function.path("boost").doubleValue();
        String interpolation = SearchJson.text(function, "interpolation");
        Interpolation shape = interpolation == null ? Interpolation.LINEAR
            : Interpolation.valueOf(interpolation.toUpperCase(Locale.ROOT));
        JsonNode settings = function.path(type);
        return switch (type) {
            case "magnitude" -> new Magnitude(path, boost, shape, settings.path("boostingRangeStart").doubleValue(),
                settings.path("boostingRangeEnd").doubleValue(), settings.path("constantBoostBeyondRange")
                .asBoolean(false));
            case "freshness" -> new Freshness(path, boost, shape,
                ScoringProfileValidator.parseDuration(SearchJson.text(settings, "boostingDuration")));
            case "distance" -> new Distance(path, boost, shape, referencePoint(profile,
                parameter(profile, parameters, SearchJson.text(settings, "referencePointParameter"))),
                settings.path("boostingDistance").doubleValue());
            default -> new Tag(path, boost, tags(index, field,
                parameter(profile, parameters, SearchJson.text(settings, "tagsParameter")).values()));
        };
    }

    /** The tags as the field's values are compared: through its normalizer, if it names one. */
    private static Set<String> tags(LiveIndex index, SearchFieldDefinition field, List<String> values) {
        Optional<LexicalNormalizer> normalizer = index.analysis().normalizer(field);
        Set<String> tags = new HashSet<>();
        for (String value : values) {
            tags.add(normalizer.isPresent() ? normalizer.get().normalize(value) : value);
        }
        return tags;
    }

    private static NamedValues parameter(String profile, Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.get(name);
        if (values == null || values.isEmpty()) {
            throw SearchException.badRequest("The scoring profile '" + profile + "' requires a value for the scoring "
                + "parameter '" + name + "'; pass it in scoringParameters as '" + name + "-value'.\r\nParameter "
                + "name: scoringParameter");
        }
        return new NamedValues(name, values);
    }

    private static GeoPoint referencePoint(String profile, NamedValues parameter) {
        List<String> values = parameter.values();
        try {
            if (values.size() == 2) {
                double longitude = Double.parseDouble(values.get(0).trim());
                double latitude = Double.parseDouble(values.get(1).trim());
                if (Math.abs(longitude) <= 180 && Math.abs(latitude) <= 90) {
                    return new GeoPoint(longitude, latitude);
                }
            }
        } catch (NumberFormatException ignored) {
            // Falls through to the error below, which names the expected format.
        }
        throw SearchException.badRequest("The scoring parameter '" + parameter.name() + "' of the scoring profile '"
            + profile + "' must be a point as 'longitude,latitude', such as '" + parameter.name()
            + "--122.2,44.8'.\r\nParameter name: scoringParameter");
    }

    String name() {
        return name;
    }

    /** The per-field multipliers of text scores, by field path. */
    Map<String, Double> textWeights() {
        return textWeights;
    }

    boolean hasFunctions() {
        return !functions.isEmpty();
    }

    /** The multiplier of the document's score. */
    double multiplier(IndexedDocument document, Instant now) {
        double combined = switch (aggregation) {
            case "average", "minimum", "maximum", "sum" -> {
                List<Double> contributions = new ArrayList<>(functions.size());
                for (Function function : functions) {
                    double position = function.position(document, now);
                    contributions.add(position < 0 ? 0 : (function.boost() - 1) * position);
                }
                yield switch (aggregation) {
                    case "average" -> contributions.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    case "minimum" -> contributions.stream().mapToDouble(Double::doubleValue).min().orElse(0);
                    case "maximum" -> contributions.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                    default -> contributions.stream().mapToDouble(Double::doubleValue).sum();
                };
            }
            case "firstmatching" -> {
                double first = 0;
                for (Function function : functions) {
                    double position = function.position(document, now);
                    if (position >= 0) {
                        first = (function.boost() - 1) * position;
                        break;
                    }
                }
                yield first;
            }
            default -> {
                double product = 1;
                for (Function function : functions) {
                    double position = function.position(document, now);
                    product *= position < 0 ? 1 : 1 + (function.boost() - 1) * position;
                }
                yield product - 1;
            }
        };
        return Math.max(0, 1 + combined);
    }

    private enum Interpolation {
        LINEAR, CONSTANT, QUADRATIC, LOGARITHMIC;

        /** The boost share at relative position {@code t} (1 at the most boosted end of the range). */
        double apply(double t) {
            return switch (this) {
                case LINEAR -> t;
                case CONSTANT -> 1;
                case QUADRATIC -> 1 - (1 - t) * (1 - t);
                case LOGARITHMIC -> 1 - Math.log10(1 + 9 * (1 - t));
            };
        }
    }

    private record NamedValues(String name, List<String> values) {
    }

    /** One scoring function. */
    private sealed interface Function permits Magnitude, Freshness, Distance, Tag {

        double boost();

        /** The interpolated position of the document in the function's range, or -1 when it is out of range. */
        double position(IndexedDocument document, Instant now);
    }

    private static Object value(IndexedDocument document, List<String> path) {
        return DocumentValues.valueAt(document.values(), path);
    }

    private record Magnitude(List<String> path, double boost, Interpolation interpolation, double start, double end,
                             boolean constantBeyondRange) implements Function {

        @Override
        public double position(IndexedDocument document, Instant now) {
            if (!(value(document, path) instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                return -1;
            }
            double x = number.doubleValue();
            if (start == end) {
                return x == end ? 1 : (constantBeyondRange && x > end ? 1 : -1);
            }
            double t = (x - start) / (end - start);
            if (t > 1) {
                return constantBeyondRange ? 1 : -1;
            }
            return t < 0 ? -1 : interpolation.apply(t);
        }
    }

    private record Freshness(List<String> path, double boost, Interpolation interpolation, Duration duration)
            implements Function {

        @Override
        public double position(IndexedDocument document, Instant now) {
            if (!(value(document, path) instanceof Instant instant) || duration.isZero()) {
                return -1;
            }
            double window = Math.abs(duration.toMillis());
            double distance = duration.isNegative() ? instant.toEpochMilli() - now.toEpochMilli()
                : now.toEpochMilli() - instant.toEpochMilli();
            if (distance < 0 || distance > window) {
                return -1;
            }
            return interpolation.apply(1 - distance / window);
        }
    }

    private record Distance(List<String> path, double boost, Interpolation interpolation, GeoPoint reference,
                            double boostingDistance) implements Function {

        @Override
        public double position(IndexedDocument document, Instant now) {
            if (!(value(document, path) instanceof GeoPoint point)) {
                return -1;
            }
            double distance = reference.distanceKm(point);
            return distance > boostingDistance ? -1 : interpolation.apply(1 - distance / boostingDistance);
        }
    }

    private record Tag(List<String> path, double boost, Set<String> tags) implements Function {

        @Override
        public double position(IndexedDocument document, Instant now) {
            Object value = value(document, path);
            if (value instanceof List<?> list) {
                for (Object element : list) {
                    if (element != null && tags.contains(element.toString())) {
                        return 1;
                    }
                }
                return -1;
            }
            return value != null && tags.contains(value.toString()) ? 1 : -1;
        }
    }
}
