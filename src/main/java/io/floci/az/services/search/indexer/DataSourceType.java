package io.floci.az.services.search.indexer;

import java.util.Locale;
import java.util.Optional;

/**
 * The {@code type} of a data source ({@code SearchIndexerDataSourceType}), and whether floci-az can read it. Every
 * documented type is accepted on create; only the ones floci-az serves in process can be indexed.
 */
public enum DataSourceType {
    AZURE_BLOB("azureblob", true),
    ADLS_GEN2("adlsgen2", true),
    AZURE_TABLE("azuretable", true),
    COSMOS_DB("cosmosdb", true),
    /** The {@code cosmosdb} type's name in api-version 2017-11-11. */
    DOCUMENT_DB("documentdb", true),
    AZURE_SQL("azuresql", false),
    MYSQL("mysql", false),
    ONELAKE("onelake", false),
    SHAREPOINT("sharepoint", false),
    AZURE_FILE("azurefile", false);

    private final String wireName;
    private final boolean served;

    DataSourceType(String wireName, boolean served) {
        this.wireName = wireName;
        this.served = served;
    }

    public String wireName() {
        return wireName;
    }

    /** Whether floci-az reads this kind of source in process. */
    public boolean served() {
        return served;
    }

    /** Whether the source is a blob container: Blob Storage or ADLS Gen2. */
    public boolean isBlob() {
        return this == AZURE_BLOB || this == ADLS_GEN2;
    }

    /** Whether the source is an Azure Cosmos DB for NoSQL container. */
    public boolean isCosmos() {
        return this == COSMOS_DB || this == DOCUMENT_DB;
    }

    /** The type named {@code value}, ignoring case. */
    public static Optional<DataSourceType> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        for (DataSourceType type : values()) {
            if (type.wireName.equals(lower)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
