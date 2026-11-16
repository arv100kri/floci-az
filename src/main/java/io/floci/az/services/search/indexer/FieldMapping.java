package io.floci.az.services.search.indexer;

/**
 * One field mapping of an indexer ({@code FieldMapping}).
 *
 * @param sourceFieldName the source field: a name, or a JSON Pointer ({@code /article/text}) into a JSON source
 * @param targetFieldName the index field, or null for the source field's own name
 * @param function        the mapping function, or null
 */
public record FieldMapping(String sourceFieldName, String targetFieldName, MappingFunction function) {

    /** The index field the mapping writes: the target, or the source's name (the last pointer segment). */
    public String effectiveTarget() {
        if (targetFieldName != null && !targetFieldName.isBlank()) {
            return targetFieldName;
        }
        String source = sourceFieldName.endsWith("/") && sourceFieldName.length() > 1
            ? sourceFieldName.substring(0, sourceFieldName.length() - 1) : sourceFieldName;
        int slash = source.lastIndexOf('/');
        return slash < 0 ? source : source.substring(slash + 1);
    }

    /** The source field's top-level name: the name itself, or the first segment of a pointer. */
    public String sourceRoot() {
        if (!sourceFieldName.startsWith("/")) {
            return sourceFieldName;
        }
        String rest = sourceFieldName.substring(1);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }
}
