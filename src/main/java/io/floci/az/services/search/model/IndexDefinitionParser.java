package io.floci.az.services.search.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates an index definition from a create or update request and normalises it to what GET returns: every field
 * attribute present with its documented default, every top-level section present (empty arrays, null objects, the
 * BM25 similarity), and sections floci-az does not interpret yet kept verbatim so they round-trip.
 *
 * <p>The rules are the documented ones from Create Index (REST) and the field reference: exactly one {@code Edm.String}
 * key field at the top level, searchable only for text and vector fields, sortable only for single-valued fields,
 * facetable not for geography points, analyzers only on searchable fields, normalizers only on filterable, sortable or
 * facetable text, vector fields with dimensions and a profile, and nulls on complex fields.</p>
 */
public final class IndexDefinitionParser {

    /** The {@code @odata.type} of the BM25 similarity, the only one a new service accepts. */
    public static final String BM25_SIMILARITY = "#Microsoft.Azure.Search.BM25Similarity";

    private static final String CLASSIC_SIMILARITY = "#Microsoft.Azure.Search.ClassicSimilarity";
    private static final String ODATA_TYPE = "@odata.type";
    private static final int MAX_FIELDS = 1000;
    private static final int MAX_DEPTH = 10;
    private static final int MAX_COMPLEX_COLLECTIONS = 40;
    private static final int MAX_DIMENSIONS = 4096;
    private static final Pattern INDEX_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{1,127}");
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,127}");
    private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_]{0,127}");
    private static final List<String> ARRAY_SECTIONS = List.of(
        "scoringProfiles", "suggesters", "analyzers", "normalizers", "tokenizers", "tokenFilters", "charFilters");
    private static final List<String> OBJECT_SECTIONS = List.of("corsOptions", "encryptionKey");
    private static final List<String> TRAILING_OBJECT_SECTIONS = List.of("semantic", "vectorSearch");
    private static final List<String> FIELD_ATTRIBUTE_ORDER = List.of(
        "name", "type", "searchable", "filterable", "retrievable", "stored", "sortable", "facetable", "key");
    private static final List<String> FIELD_TRAILING_ORDER = List.of(
        "indexAnalyzer", "searchAnalyzer", "analyzer", "normalizer", "dimensions", "vectorSearchProfile",
        "vectorEncoding", "synonymMaps", "fields");
    private static final Set<String> SIMPLE_ONLY_ATTRIBUTES = Set.of(
        "key", "retrievable", "stored", "searchable", "filterable", "sortable", "facetable", "analyzer",
        "searchAnalyzer", "indexAnalyzer", "normalizer", "dimensions", "vectorSearchProfile", "vectorEncoding");

    private final AnalysisCatalog catalog;
    private final Set<String> customAnalyzers = new HashSet<>();
    private final Set<String> customNormalizers = new HashSet<>();
    private final Set<String> vectorProfiles = new HashSet<>();
    private int fieldCount;
    private int complexCollections;

    private IndexDefinitionParser(AnalysisCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Validates and normalises {@code body}.
     *
     * @param body      the request body
     * @param nameInUrl the index name the URL names, or null for a create by POST
     * @param catalog   the built-in analyzer and normalizer names
     */
    public static SearchIndexDefinition parse(JsonNode body, String nameInUrl, AnalysisCatalog catalog) {
        if (!(body instanceof ObjectNode input)) {
            throw SearchException.badRequest("The request is invalid. Details: definition : the index definition "
                + "must be a JSON object.");
        }
        return new IndexDefinitionParser(catalog).parseIndex(input, nameInUrl);
    }

    /** Whether {@code name} follows the documented naming rule for indexes. */
    public static boolean isValidIndexName(String name) {
        return name != null && INDEX_NAME.matcher(name).matches() && !name.contains("--") && !name.contains("__");
    }

    private SearchIndexDefinition parseIndex(ObjectNode input, String nameInUrl) {
        String name = SearchJson.text(input, "name");
        if (name == null || name.isBlank()) {
            if (nameInUrl == null) {
                throw invalid("the index name is required.");
            }
            name = nameInUrl;
        } else if (nameInUrl != null && !name.equals(nameInUrl)) {
            throw invalid("the index name '" + name + "' in the request body does not match the name '"
                + nameInUrl + "' in the request URL.");
        }
        if (!isValidIndexName(name)) {
            throw invalid("Index name '" + name + "' is invalid. Index names must be 2 to 128 characters long, "
                + "contain only lowercase letters, digits, dashes or underscores, start with a letter or digit, and "
                + "not contain consecutive dashes or underscores.");
        }
        collectNames(input, "analyzers", customAnalyzers);
        collectNames(input, "normalizers", customNormalizers);
        collectVectorProfiles(input);

        JsonNode fieldsNode = input.get("fields");
        if (!(fieldsNode instanceof ArrayNode fieldArray) || fieldArray.isEmpty()) {
            throw invalid("the index must define at least one field.");
        }
        List<SearchFieldDefinition> fields = new ArrayList<>();
        ArrayNode normalizedFields = SearchJson.NODES.arrayNode();
        Set<String> names = new HashSet<>();
        for (JsonNode fieldNode : fieldArray) {
            SearchFieldDefinition field = parseField(fieldNode, null, false, 1, names);
            fields.add(field);
            normalizedFields.add(field.json());
        }
        if (fieldCount > MAX_FIELDS) {
            throw invalid("the index has " + fieldCount + " fields; the maximum is " + MAX_FIELDS + ".");
        }
        if (complexCollections > MAX_COMPLEX_COLLECTIONS) {
            throw invalid("the index has " + complexCollections + " fields of type Collection(Edm.ComplexType); "
                + "the maximum is " + MAX_COMPLEX_COLLECTIONS + ".");
        }
        List<SearchFieldDefinition> keys = fields.stream().filter(SearchFieldDefinition::key).toList();
        if (keys.size() != 1) {
            throw invalid("the index must have exactly one key field of type Edm.String, but "
                + (keys.isEmpty() ? "none was" : keys.size() + " were") + " found.");
        }

        ObjectNode similarity = normalizeSimilarity(input.get("similarity"));
        SearchIndexDefinition skeleton = new SearchIndexDefinition(name, SearchJson.object(), fields, keys.get(0),
            null, null);
        ObjectNode output = SearchJson.object();
        output.put("name", name);
        if (input.has("description")) {
            output.set("description", input.get("description"));
        }
        output.set("defaultScoringProfile", nullToNullNode(input.get("defaultScoringProfile")));
        output.set("fields", normalizedFields);
        for (String section : ARRAY_SECTIONS) {
            output.set(section, arraySection(input, section));
        }
        for (String section : OBJECT_SECTIONS) {
            output.set(section, objectSection(input, section));
        }
        output.set("similarity", similarity);
        for (String section : TRAILING_OBJECT_SECTIONS) {
            output.set(section, objectSection(input, section));
        }
        output.set("vectorSearch", VectorSearchValidator.normalize(output.get("vectorSearch"), skeleton));
        Iterator<Map.Entry<String, JsonNode>> extras = input.fields();
        while (extras.hasNext()) {
            Map.Entry<String, JsonNode> extra = extras.next();
            if (!output.has(extra.getKey()) && !extra.getKey().startsWith("@odata.")) {
                output.set(extra.getKey(), extra.getValue().deepCopy());
            }
        }

        validateScoringProfiles(output);
        ScoringProfileValidator.validate(output, skeleton);
        validateSuggesters(output, skeleton, catalog);
        validateCors(output.get("corsOptions"));
        output.set("semantic", SemanticDefinitionValidator.validate(output.get("semantic"), skeleton));
        validateNamedComponents(output, "analyzers");
        validateNamedComponents(output, "normalizers");
        validateNamedComponents(output, "tokenizers");
        validateNamedComponents(output, "tokenFilters");
        validateNamedComponents(output, "charFilters");
        PermissionFilterValidator.validate(output, fields);
        catalog.validateAnalysis(output);
        return new SearchIndexDefinition(name, output, fields, keys.get(0), number(similarity.get("k1")),
            number(similarity.get("b")));
    }

    private SearchFieldDefinition parseField(JsonNode node, String parentPath, boolean parentMultiValued, int depth,
                                             Set<String> siblingNames) {
        if (!(node instanceof ObjectNode input)) {
            throw invalid("each field must be a JSON object.");
        }
        fieldCount++;
        String name = SearchJson.text(input, "name");
        if (name == null || !FIELD_NAME.matcher(name).matches() || name.startsWith("azureSearch")) {
            throw invalid("Field name '" + name + "' is invalid. Field names must start with a letter, contain only "
                + "letters, digits or underscores, be at most 128 characters long and not start with 'azureSearch'.");
        }
        String path = parentPath == null ? name : parentPath + "/" + name;
        if (!siblingNames.add(name)) {
            throw invalid("the field name '" + path + "' is used more than once.");
        }
        String typeName = SearchJson.text(input, "type");
        FieldType type = FieldType.parse(typeName).orElseThrow(
            () -> invalid("the field '" + path + "' has an invalid type '" + typeName + "'."));
        if (type.element().isVectorElement() && !type.collection()) {
            throw invalid("the field '" + path + "' has type '" + type.edmName() + "', which is only valid as the "
                + "element of a vector field collection.");
        }
        return type.isComplex()
            ? parseComplexField(input, name, path, type, parentMultiValued, depth)
            : parseSimpleField(input, name, path, type, parentMultiValued);
    }

    private SearchFieldDefinition parseComplexField(ObjectNode input, String name, String path, FieldType type,
                                                    boolean parentMultiValued, int depth) {
        if (depth > MAX_DEPTH) {
            throw invalid("the field '" + path + "' is nested deeper than the maximum depth of " + MAX_DEPTH + ".");
        }
        for (String attribute : SIMPLE_ONLY_ATTRIBUTES) {
            JsonNode value = input.get(attribute);
            if (value != null && !value.isNull() && !(value.isBoolean() && !value.booleanValue())) {
                throw invalid("the complex field '" + path + "' cannot set '" + attribute + "'; it must be null for "
                    + "fields of type " + type.edmName() + ".");
            }
        }
        JsonNode synonyms = input.get("synonymMaps");
        if (synonyms != null && synonyms.isArray() && !synonyms.isEmpty()) {
            throw invalid("the complex field '" + path + "' cannot set 'synonymMaps'.");
        }
        JsonNode subfieldsNode = input.get("fields");
        if (!(subfieldsNode instanceof ArrayNode subfieldArray) || subfieldArray.isEmpty()) {
            throw invalid("the complex field '" + path + "' must define at least one subfield.");
        }
        if (type.collection()) {
            complexCollections++;
        }
        boolean multiValued = parentMultiValued || type.collection();
        List<SearchFieldDefinition> subfields = new ArrayList<>();
        ArrayNode subfieldJson = SearchJson.NODES.arrayNode();
        Set<String> names = new HashSet<>();
        for (JsonNode subfieldNode : subfieldArray) {
            SearchFieldDefinition subfield = parseField(subfieldNode, path, multiValued, depth + 1, names);
            subfields.add(subfield);
            subfieldJson.add(subfield.json());
        }
        ObjectNode json = SearchJson.object();
        json.put("name", name);
        json.put("type", type.edmName());
        for (String attribute : FIELD_ATTRIBUTE_ORDER.subList(2, FIELD_ATTRIBUTE_ORDER.size())) {
            json.putNull(attribute);
        }
        copyExtras(input, json);
        for (String attribute : FIELD_TRAILING_ORDER) {
            switch (attribute) {
                case "synonymMaps" -> json.set(attribute, SearchJson.NODES.arrayNode());
                case "fields" -> json.set(attribute, subfieldJson);
                default -> json.putNull(attribute);
            }
        }
        return new SearchFieldDefinition(name, path, type, false, false, false, false, false, false, false, null,
            null, null, null, null, null, null, List.of(), subfields, multiValued, json);
    }

    private SearchFieldDefinition parseSimpleField(ObjectNode input, String name, String path, FieldType type,
                                                   boolean parentMultiValued) {
        JsonNode subfields = input.get("fields");
        if (subfields != null && subfields.isArray() && !subfields.isEmpty()) {
            throw invalid("the field '" + path + "' of type " + type.edmName() + " cannot have subfields.");
        }
        boolean multiValued = parentMultiValued || type.collection();
        boolean vector = type.isVector();
        Boolean keyAttr = bool(input, "key", path);
        Boolean retrievableAttr = bool(input, "retrievable", path);
        Boolean storedAttr = bool(input, "stored", path);
        Boolean searchableAttr = bool(input, "searchable", path);
        Boolean filterableAttr = bool(input, "filterable", path);
        Boolean sortableAttr = bool(input, "sortable", path);
        Boolean facetableAttr = bool(input, "facetable", path);
        String analyzer = optionalText(input, "analyzer", path);
        String searchAnalyzer = optionalText(input, "searchAnalyzer", path);
        String indexAnalyzer = optionalText(input, "indexAnalyzer", path);
        String normalizer = optionalText(input, "normalizer", path);
        String vectorSearchProfile = optionalText(input, "vectorSearchProfile", path);
        String vectorEncoding = optionalText(input, "vectorEncoding", path);
        Integer dimensions = optionalInt(input, "dimensions", path);
        List<String> synonymMaps = stringList(input, "synonymMaps", path);

        boolean key = Boolean.TRUE.equals(keyAttr);
        if (key && (path.contains("/") || type.collection() || type.element() != EdmType.STRING)) {
            throw invalid("the key field '" + path + "' must be a top-level field of type Edm.String.");
        }
        boolean searchable;
        boolean filterable;
        boolean sortable;
        boolean facetable;
        boolean retrievable;
        boolean stored = storedAttr == null || storedAttr;
        if (vector) {
            if (key) {
                throw invalid("the vector field '" + path + "' cannot be the key field.");
            }
            if (Boolean.FALSE.equals(searchableAttr)) {
                throw invalid("the vector field '" + path + "' must be searchable.");
            }
            rejectTrue(filterableAttr, path, "filterable", "vector fields");
            rejectTrue(sortableAttr, path, "sortable", "vector fields");
            rejectTrue(facetableAttr, path, "facetable", "vector fields");
            if (dimensions == null) {
                throw invalid("the vector field '" + path + "' must set 'dimensions'.");
            }
            if (dimensions < 2 || dimensions > MAX_DIMENSIONS) {
                throw invalid("the vector field '" + path + "' has " + dimensions + " dimensions; vector fields "
                    + "have between 2 and " + MAX_DIMENSIONS + ".");
            }
            if (vectorSearchProfile == null) {
                throw invalid("the vector field '" + path + "' must name a 'vectorSearchProfile'.");
            }
            if (!vectorProfiles.contains(vectorSearchProfile)) {
                throw invalid("the vector field '" + path + "' names the vector search profile '"
                    + vectorSearchProfile + "', which the index's vectorSearch.profiles does not define.");
            }
            if (vectorEncoding != null && (!"packedBit".equals(vectorEncoding) || type.element() != EdmType.BYTE)) {
                throw invalid("the vector field '" + path + "' has vectorEncoding '" + vectorEncoding + "'; only "
                    + "'packedBit' is supported, and only for Collection(Edm.Byte).");
            }
            if (analyzer != null || searchAnalyzer != null || indexAnalyzer != null || normalizer != null) {
                throw invalid("the vector field '" + path + "' cannot set an analyzer or a normalizer.");
            }
            if (!synonymMaps.isEmpty()) {
                throw invalid("the vector field '" + path + "' cannot set 'synonymMaps'.");
            }
            searchable = true;
            filterable = false;
            sortable = false;
            facetable = false;
            retrievable = retrievableAttr != null && retrievableAttr;
            if (!stored && retrievable) {
                throw invalid("the vector field '" + path + "' sets 'stored' to false, so 'retrievable' must be "
                    + "false too.");
            }
        } else {
            if (dimensions != null || vectorSearchProfile != null || vectorEncoding != null) {
                throw invalid("the field '" + path + "' of type " + type.edmName() + " cannot set 'dimensions', "
                    + "'vectorSearchProfile' or 'vectorEncoding'; they are for vector fields.");
            }
            if (!stored) {
                throw invalid("the field '" + path + "' sets 'stored' to false, which is only allowed for vector "
                    + "fields.");
            }
            boolean text = type.isText();
            if (Boolean.TRUE.equals(searchableAttr) && !text) {
                throw invalid("the field '" + path + "' of type " + type.edmName() + " cannot be searchable; only "
                    + "Edm.String and Collection(Edm.String) fields are.");
            }
            searchable = searchableAttr == null ? text : searchableAttr;
            filterable = filterableAttr == null || filterableAttr;
            boolean geography = type.element() == EdmType.GEOGRAPHY_POINT;
            if (Boolean.TRUE.equals(facetableAttr) && geography) {
                throw invalid("the field '" + path + "' of type " + type.edmName() + " cannot be facetable.");
            }
            facetable = facetableAttr == null ? !geography : facetableAttr;
            if (Boolean.TRUE.equals(sortableAttr) && multiValued) {
                throw invalid("the field '" + path + "' cannot be sortable because it is multi-valued.");
            }
            sortable = sortableAttr == null ? !multiValued : sortableAttr;
            retrievable = retrievableAttr == null || retrievableAttr;
            if (key && !retrievable) {
                throw invalid("the key field '" + path + "' must be retrievable.");
            }
            validateAnalyzers(path, searchable, analyzer, searchAnalyzer, indexAnalyzer);
            if (normalizer != null) {
                if (!text || !(filterable || sortable || facetable)) {
                    throw invalid("the field '" + path + "' can only set a normalizer when it is a filterable, "
                        + "sortable or facetable text field.");
                }
                if (!catalog.isBuiltInNormalizer(normalizer) && !customNormalizers.contains(normalizer)) {
                    throw invalid("the field '" + path + "' names the normalizer '" + normalizer + "', which is "
                        + "neither built in nor defined by the index.");
                }
            }
            if (!synonymMaps.isEmpty() && !searchable) {
                throw invalid("the field '" + path + "' can only set 'synonymMaps' when it is searchable.");
            }
            if (synonymMaps.size() > 1) {
                throw invalid("the field '" + path + "' names " + synonymMaps.size() + " synonym maps; only one "
                    + "synonym map per field is supported.");
            }
        }

        ObjectNode json = SearchJson.object();
        json.put("name", name);
        json.put("type", type.edmName());
        json.put("searchable", searchable);
        json.put("filterable", filterable);
        json.put("retrievable", retrievable);
        json.put("stored", stored);
        json.put("sortable", sortable);
        json.put("facetable", facetable);
        json.put("key", key);
        copyExtras(input, json);
        putText(json, "indexAnalyzer", indexAnalyzer);
        putText(json, "searchAnalyzer", searchAnalyzer);
        putText(json, "analyzer", analyzer);
        putText(json, "normalizer", normalizer);
        if (dimensions == null) {
            json.putNull("dimensions");
        } else {
            json.put("dimensions", dimensions);
        }
        putText(json, "vectorSearchProfile", vectorSearchProfile);
        putText(json, "vectorEncoding", vectorEncoding);
        ArrayNode synonymJson = json.putArray("synonymMaps");
        synonymMaps.forEach(synonymJson::add);
        return new SearchFieldDefinition(name, path, type, key, retrievable, stored, searchable, filterable, sortable,
            facetable, analyzer, searchAnalyzer, indexAnalyzer, normalizer, dimensions, vectorSearchProfile,
            vectorEncoding, synonymMaps, List.of(), multiValued, json);
    }

    private void validateAnalyzers(String path, boolean searchable, String analyzer, String searchAnalyzer,
                                   String indexAnalyzer) {
        if (analyzer == null && searchAnalyzer == null && indexAnalyzer == null) {
            return;
        }
        if (!searchable) {
            throw invalid("the field '" + path + "' can only set an analyzer when it is searchable.");
        }
        if (analyzer != null && (searchAnalyzer != null || indexAnalyzer != null)) {
            throw invalid("the field '" + path + "' cannot set 'analyzer' together with 'searchAnalyzer' or "
                + "'indexAnalyzer'.");
        }
        if ((searchAnalyzer == null) != (indexAnalyzer == null)) {
            throw invalid("the field '" + path + "' must set 'searchAnalyzer' and 'indexAnalyzer' together.");
        }
        for (String name : new String[] {analyzer, searchAnalyzer, indexAnalyzer}) {
            if (name != null && !catalog.isBuiltInAnalyzer(name) && !customAnalyzers.contains(name)) {
                throw invalid("the field '" + path + "' names the analyzer '" + name + "', which is neither built "
                    + "in nor defined by the index.");
            }
        }
        for (String name : new String[] {searchAnalyzer, indexAnalyzer}) {
            if (name != null && catalog.isLanguageAnalyzer(name)) {
                throw invalid("the field '" + path + "' sets the language analyzer '" + name + "' as its "
                    + "searchAnalyzer or indexAnalyzer; use 'analyzer' for language analyzers.");
            }
        }
    }

    private static ObjectNode normalizeSimilarity(JsonNode node) {
        ObjectNode similarity = SearchJson.object();
        similarity.put(ODATA_TYPE, BM25_SIMILARITY);
        if (node == null || node.isNull()) {
            similarity.putNull("k1");
            similarity.putNull("b");
            return similarity;
        }
        if (!node.isObject()) {
            throw invalid("'similarity' must be an object.");
        }
        String type = SearchJson.text(node, ODATA_TYPE);
        if (CLASSIC_SIMILARITY.equalsIgnoreCase(type)) {
            throw invalid("the ClassicSimilarity algorithm is not supported by this search service; use "
                + BM25_SIMILARITY + ".");
        }
        if (type == null || !BM25_SIMILARITY.equalsIgnoreCase(type)) {
            throw invalid("'similarity' has an unknown @odata.type '" + type + "'.");
        }
        Double k1 = similarityParameter(node, "k1");
        Double b = similarityParameter(node, "b");
        if (k1 != null && k1 < 0) {
            throw invalid("the BM25 parameter 'k1' must not be negative.");
        }
        if (b != null && (b < 0 || b > 1)) {
            throw invalid("the BM25 parameter 'b' must be between 0 and 1.");
        }
        if (k1 == null) {
            similarity.putNull("k1");
        } else {
            similarity.put("k1", k1);
        }
        if (b == null) {
            similarity.putNull("b");
        } else {
            similarity.put("b", b);
        }
        return similarity;
    }

    private static Double similarityParameter(JsonNode similarity, String name) {
        JsonNode value = similarity.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw invalid("the BM25 parameter '" + name + "' must be a number.");
        }
        return value.doubleValue();
    }

    private static void validateScoringProfiles(ObjectNode index) {
        Set<String> names = new HashSet<>();
        for (JsonNode profile : index.get("scoringProfiles")) {
            String name = SearchJson.text(profile, "name");
            if (name == null || !PROFILE_NAME.matcher(name).matches()) {
                throw invalid("scoring profile name '" + name + "' is invalid.");
            }
            if (!names.add(name)) {
                throw invalid("the scoring profile name '" + name + "' is used more than once.");
            }
        }
        JsonNode defaultProfile = index.get("defaultScoringProfile");
        if (defaultProfile != null && defaultProfile.isTextual() && !names.contains(defaultProfile.textValue())) {
            throw invalid("the default scoring profile '" + defaultProfile.textValue() + "' is not defined in "
                + "'scoringProfiles'.");
        }
    }

    private static void validateSuggesters(ObjectNode index, SearchIndexDefinition fields, AnalysisCatalog catalog) {
        ArrayNode suggesters = (ArrayNode) index.get("suggesters");
        if (suggesters.size() > 1) {
            throw invalid("an index can define only one suggester.");
        }
        for (JsonNode suggester : suggesters) {
            if (!(suggester instanceof ObjectNode object)) {
                throw invalid("each suggester must be a JSON object.");
            }
            String name = SearchJson.text(object, "name");
            if (name == null || !PROFILE_NAME.matcher(name).matches()) {
                throw invalid("suggester name '" + name + "' is invalid.");
            }
            String mode = SearchJson.text(object, "searchMode");
            if (mode == null) {
                object.put("searchMode", "analyzingInfixMatching");
            } else if (!"analyzingInfixMatching".equalsIgnoreCase(mode)) {
                throw invalid("the suggester '" + name + "' has searchMode '" + mode + "'; only "
                    + "'analyzingInfixMatching' is supported.");
            }
            JsonNode sourceFields = object.get("sourceFields");
            if (!(sourceFields instanceof ArrayNode sources) || sources.isEmpty()) {
                throw invalid("the suggester '" + name + "' must list at least one source field.");
            }
            for (JsonNode source : sources) {
                String path = source.isTextual() ? source.textValue() : null;
                SearchFieldDefinition field = path == null ? null : fields.field(path).orElse(null);
                if (field == null || !field.type().isText()) {
                    throw invalid("the suggester '" + name + "' names the source field '" + path + "', which is "
                        + "not a text field of the index.");
                }
                if (!field.searchable()) {
                    throw invalid("the suggester '" + name + "' names the source field '" + path + "', which is "
                        + "not searchable.");
                }
                String analyzer = field.analyzer() != null ? field.analyzer() : field.indexAnalyzer();
                if (analyzer != null && !SearchFieldDefinition.DEFAULT_ANALYZER.equals(analyzer)
                        && !catalog.isLanguageAnalyzer(analyzer)) {
                    throw invalid("the suggester '" + name + "' names the source field '" + path + "', whose "
                        + "analyzer '" + analyzer + "' suggesters do not support; use the standard Lucene analyzer or "
                        + "a language analyzer.");
                }
            }
        }
    }

    private static void validateCors(JsonNode cors) {
        if (cors == null || cors.isNull()) {
            return;
        }
        JsonNode origins = cors.get("allowedOrigins");
        if (!(origins instanceof ArrayNode originArray)) {
            throw invalid("'corsOptions' must list 'allowedOrigins'.");
        }
        for (JsonNode origin : originArray) {
            if (!origin.isTextual()) {
                throw invalid("'corsOptions.allowedOrigins' must contain strings.");
            }
        }
    }

    private static void validateNamedComponents(ObjectNode index, String section) {
        Set<String> names = new HashSet<>();
        for (JsonNode component : index.get(section)) {
            String name = SearchJson.text(component, "name");
            if (name == null || name.isBlank()) {
                throw invalid("each entry of '" + section + "' must have a name.");
            }
            if (SearchJson.text(component, ODATA_TYPE) == null) {
                throw invalid("the entry '" + name + "' of '" + section + "' must set '@odata.type'.");
            }
            if (!names.add(name)) {
                throw invalid("the name '" + name + "' is used more than once in '" + section + "'.");
            }
        }
    }

    private static void collectNames(ObjectNode input, String section, Set<String> names) {
        JsonNode array = input.get(section);
        if (array != null && array.isArray()) {
            for (JsonNode entry : array) {
                String name = SearchJson.text(entry, "name");
                if (name != null) {
                    names.add(name);
                }
            }
        }
    }

    private void collectVectorProfiles(ObjectNode input) {
        JsonNode vectorSearch = input.get("vectorSearch");
        JsonNode profiles = vectorSearch == null ? null : vectorSearch.get("profiles");
        if (profiles != null && profiles.isArray()) {
            for (JsonNode profile : profiles) {
                String name = SearchJson.text(profile, "name");
                if (name != null) {
                    vectorProfiles.add(name);
                }
            }
        }
    }

    private static JsonNode arraySection(ObjectNode input, String section) {
        JsonNode value = input.get(section);
        if (value == null || value.isNull()) {
            return SearchJson.NODES.arrayNode();
        }
        if (!value.isArray()) {
            throw invalid("'" + section + "' must be an array.");
        }
        return value.deepCopy();
    }

    private static JsonNode objectSection(ObjectNode input, String section) {
        JsonNode value = input.get(section);
        if (value == null || value.isNull()) {
            return NullNode.getInstance();
        }
        if (!value.isObject()) {
            throw invalid("'" + section + "' must be an object.");
        }
        return value.deepCopy();
    }

    private static void copyExtras(ObjectNode input, ObjectNode json) {
        Iterator<Map.Entry<String, JsonNode>> entries = input.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String name = entry.getKey();
            if (!FIELD_ATTRIBUTE_ORDER.contains(name) && !FIELD_TRAILING_ORDER.contains(name)
                    && !name.startsWith("@")) {
                json.set(name, entry.getValue().deepCopy());
            }
        }
    }

    private static Boolean bool(ObjectNode input, String attribute, String path) {
        JsonNode value = input.get(attribute);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw invalid("the attribute '" + attribute + "' of the field '" + path + "' must be true or false.");
        }
        return value.booleanValue();
    }

    private static String optionalText(ObjectNode input, String attribute, String path) {
        JsonNode value = input.get(attribute);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid("the attribute '" + attribute + "' of the field '" + path + "' must be a string.");
        }
        return value.textValue();
    }

    private static Integer optionalInt(ObjectNode input, String attribute, String path) {
        JsonNode value = input.get(attribute);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt() || !value.isIntegralNumber()) {
            throw invalid("the attribute '" + attribute + "' of the field '" + path + "' must be an integer.");
        }
        return value.intValue();
    }

    private static List<String> stringList(ObjectNode input, String attribute, String path) {
        JsonNode value = input.get(attribute);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw invalid("the attribute '" + attribute + "' of the field '" + path + "' must be an array.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw invalid("the attribute '" + attribute + "' of the field '" + path + "' must list strings.");
            }
            values.add(element.textValue());
        }
        return values;
    }

    private static void rejectTrue(Boolean value, String path, String attribute, String kind) {
        if (Boolean.TRUE.equals(value)) {
            throw invalid("the field '" + path + "' cannot be " + attribute + "; " + kind + " never are.");
        }
    }

    private static void putText(ObjectNode json, String name, String value) {
        if (value == null) {
            json.putNull(name);
        } else {
            json.put(name, value);
        }
    }

    private static JsonNode nullToNullNode(JsonNode value) {
        return value == null ? NullNode.getInstance() : value.deepCopy();
    }

    private static Double number(JsonNode value) {
        return value == null || value.isNull() ? null : value.doubleValue();
    }

    private static SearchException invalid(String detail) {
        return SearchException.badRequest("The request is invalid. Details: definition : " + detail);
    }
}
