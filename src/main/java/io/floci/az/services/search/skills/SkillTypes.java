package io.floci.az.services.search.skills;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every skill {@code @odata.type} the public REST specification defines, stable and preview, with the parameters a
 * definition is normalised with (absent ones answered as {@code null}, as the service does), and, for the skills the
 * emulator executes, the input and output names they accept.
 */
final class SkillTypes {

    static final String SPLIT = "#Microsoft.Skills.Text.SplitSkill";
    static final String MERGE = "#Microsoft.Skills.Text.MergeSkill";
    static final String SHAPER = "#Microsoft.Skills.Util.ShaperSkill";
    static final String CONDITIONAL = "#Microsoft.Skills.Util.ConditionalSkill";
    static final String DOCUMENT_EXTRACTION = "#Microsoft.Skills.Util.DocumentExtractionSkill";
    static final String WEB_API = WebApiSecrets.WEB_API_SKILL;
    static final String AZURE_OPENAI_EMBEDDING = "#Microsoft.Skills.Text.AzureOpenAIEmbeddingSkill";
    static final String AML = "#Microsoft.Skills.Custom.AmlSkill";

    private static final Map<String, SkillType> TYPES = catalog();

    private SkillTypes() {
    }

    private static Map<String, SkillType> catalog() {
        Map<String, SkillType> types = new HashMap<>();
        add(types, new SkillType(SPLIT, List.of("defaultLanguageCode", "textSplitMode", "maximumPageLength",
            "pageOverlapLength", "maximumPagesToTake"), List.of(), Set.of("text", "languageCode"),
            Set.of("textItems", "offsets", "lengths", "ordinalPositions")));
        add(types, new SkillType(MERGE, List.of("insertPreTag", "insertPostTag"), List.of(),
            Set.of("text", "itemsToInsert", "offsets"), Set.of("mergedText", "mergedOffsets")));
        add(types, new SkillType(SHAPER, List.of(), List.of(), null, Set.of("output")));
        add(types, new SkillType(CONDITIONAL, List.of(), List.of(), Set.of("condition", "whenTrue", "whenFalse"),
            Set.of("output")));
        add(types, new SkillType(DOCUMENT_EXTRACTION, List.of("parsingMode", "dataToExtract", "configuration"),
            List.of(), Set.of("file_data"), Set.of("content", "normalized_images")));
        add(types, new SkillType(WEB_API, List.of("uri", "httpMethod", "timeout", "batchSize", "degreeOfParallelism"),
            List.of("httpHeaders", "authResourceId", "authIdentity"), null, null));
        add(types, new SkillType(AZURE_OPENAI_EMBEDDING, List.of("resourceUri", "apiKey", "deploymentId",
            "dimensions", "modelName"), List.of("authIdentity"), Set.of("text"), Set.of("embedding")));
        add(types, new SkillType(AML, List.of("uri", "key", "resourceId", "timeout", "region",
            "degreeOfParallelism"), List.of(), null, null));
        notExecuted(types, "#Microsoft.Skills.Text.KeyPhraseExtractionSkill", "defaultLanguageCode",
            "maxKeyPhraseCount", "modelVersion");
        notExecuted(types, "#Microsoft.Skills.Text.LanguageDetectionSkill", "defaultCountryHint", "modelVersion");
        notExecuted(types, "#Microsoft.Skills.Text.EntityRecognitionSkill", "categories", "defaultLanguageCode",
            "minimumPrecision", "includeTypelessEntities");
        notExecuted(types, "#Microsoft.Skills.Text.V3.EntityRecognitionSkill", "categories", "defaultLanguageCode",
            "minimumPrecision", "modelVersion");
        notExecuted(types, "#Microsoft.Skills.Text.SentimentSkill", "defaultLanguageCode");
        notExecuted(types, "#Microsoft.Skills.Text.V3.SentimentSkill", "defaultLanguageCode", "modelVersion",
            "includeOpinionMining");
        notExecuted(types, "#Microsoft.Skills.Text.V3.EntityLinkingSkill", "defaultLanguageCode",
            "minimumPrecision", "modelVersion");
        notExecuted(types, "#Microsoft.Skills.Text.PIIDetectionSkill", "defaultLanguageCode", "minimumPrecision",
            "maskingMode", "maskingCharacter", "modelVersion", "piiCategories", "domain");
        notExecuted(types, "#Microsoft.Skills.Text.CustomEntityLookupSkill", "defaultLanguageCode",
            "entitiesDefinitionUri", "inlineEntitiesDefinition", "globalDefaultCaseSensitive",
            "globalDefaultAccentSensitive", "globalDefaultFuzzyEditDistance");
        notExecuted(types, "#Microsoft.Skills.Text.TranslationSkill", "defaultToLanguageCode",
            "defaultFromLanguageCode", "suggestedFrom");
        notExecuted(types, "#Microsoft.Skills.Vision.OcrSkill", "textExtractionAlgorithm", "lineEnding",
            "defaultLanguageCode", "detectOrientation");
        notExecuted(types, "#Microsoft.Skills.Vision.ImageAnalysisSkill", "defaultLanguageCode", "visualFeatures",
            "details");
        notExecuted(types, "#Microsoft.Skills.Vision.VectorizeSkill", "modelVersion");
        notExecuted(types, "#Microsoft.Skills.Util.DocumentIntelligenceLayoutSkill", "outputMode",
            "markdownHeaderDepth");
        notExecuted(types, "#Microsoft.Skills.Util.ContentUnderstandingSkill");
        notExecuted(types, "#Microsoft.Skills.Custom.ChatCompletionSkill");
        return Map.copyOf(types);
    }

    private static void add(Map<String, SkillType> types, SkillType type) {
        types.put(type.odataType(), type);
    }

    private static void notExecuted(Map<String, SkillType> types, String odataType, String... parameters) {
        add(types, new SkillType(odataType, List.of(parameters), List.of(), null, null));
    }

    /** The type named {@code odataType}, compared exactly as OData discriminators are. */
    static Optional<SkillType> find(String odataType) {
        return Optional.ofNullable(TYPES.get(odataType));
    }

    /**
     * A skill type.
     *
     * @param odataType  the {@code @odata.type}
     * @param parameters the type's parameters, in the order a definition lists them before {@code inputs}
     * @param trailing   the type's parameters listed after {@code outputs}
     * @param inputs     the input names the skill accepts; null when any name is accepted
     * @param outputs    the output names the skill produces; null when any name is accepted
     */
    record SkillType(String odataType, List<String> parameters, List<String> trailing, Set<String> inputs,
                     Set<String> outputs) {

        /** Whether the type has a {@code defaultLanguageCode}, which the service sets to {@code en} when absent. */
        boolean hasDefaultLanguage() {
            return parameters.contains("defaultLanguageCode");
        }
    }
}
