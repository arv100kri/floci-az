package io.floci.az.services.blob;

import io.floci.az.core.StoredObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Read-only, in-process access to the containers, blobs and ADLS Gen2 paths of floci-az's Blob service, for
 * emulated services that pull content from it, such as the Azure AI Search blob and ADLS Gen2 indexers. Nothing
 * here goes over HTTP, checks a credential or changes state: the caller resolves the account it reads (see
 * {@link io.floci.az.core.storage.StorageConnectionStrings}) and owns any authorization decision.
 *
 * <p><b>Consistency.</b> {@link #list} takes its snapshot under the lease monitor every Blob and Data Lake write
 * holds, so a write in flight is seen entirely or not at all: a directory rename or a recursive delete never shows
 * half of its paths. {@link #properties} and {@link #read} read one stored record, which every write replaces
 * atomically.
 *
 * <p><b>Change detection.</b> {@link BlobEntry#lastModified()} is the instant floci-az stored with the last write
 * to the blob's content, properties or metadata, at full precision (the REST surface rounds it to the second). It
 * is the value the blob indexers' built-in change detection tracks as its high-water mark.
 *
 * <p><b>Deletion detection.</b> floci-az has no blob soft delete: no delete retention policy, no
 * {@code include=deleted} listing, no undelete, no versions. A deleted blob is gone at once and no listing reports
 * it, so {@link #softDeleteSupported()} answers {@code false}. A deletion is detectable through a soft-delete
 * metadata property ({@link BlobEntry#metadata()}) or by comparing listings.
 */
@ApplicationScoped
public class BlobReader {

    private static final String CONTENT_TYPE_KEY = "Content-Type";
    private static final String CONTENT_MD5_KEY = "Content-MD5";
    private static final String NAME_KEY = "Name";
    private static final String HDI_ISFOLDER = "hdi_isfolder";

    private final BlobServiceHandler blobService;

    @Inject
    public BlobReader(BlobServiceHandler blobService) {
        this.blobService = blobService;
    }

    /**
     * One blob, or one ADLS Gen2 path in an account with a hierarchical namespace.
     *
     * @param name          the blob name (the path, for a Data Lake path) without the container
     * @param directory     whether this is an ADLS Gen2 directory: a path created as a directory through the DFS
     *                      endpoint, or in an account with a hierarchical namespace a blob whose
     *                      {@code hdi_isfolder} metadata is {@code true}. A directory has no content
     * @param contentLength the content length in bytes
     * @param contentType   the stored content type, or {@code application/octet-stream} when none was set, as Get
     *                      Blob reports it
     * @param lastModified  when the blob's content, properties or metadata last changed
     * @param etag          the ETag as the Blob service sends it, quoted
     * @param contentMd5    the Base64 MD5 of the content when one is stored (the client set
     *                      {@code x-ms-blob-content-md5}, or Put Blob From URL computed it), otherwise {@code null}
     * @param metadata      the user metadata; names match case-insensitively, as Azure's do, and floci-az stores
     *                      them in lower case
     */
    public record BlobEntry(String name, boolean directory, long contentLength, String contentType,
                            Instant lastModified, String etag, String contentMd5, Map<String, String> metadata) {
    }

    /**
     * A flat listing of a container: every blob and every stored ADLS Gen2 path, files and directories alike,
     * ordered by name as List Blobs orders them. Implicit directories (parents no client ever created) have no
     * record of their own and are not listed, as floci-az's recursive List Paths does not list them either.
     *
     * @param hierarchicalNamespace whether the account has a hierarchical namespace (ADLS Gen2)
     */
    public record BlobListing(String accountName, String containerName, boolean hierarchicalNamespace,
                              List<BlobEntry> entries) {
    }

    /**
     * A blob's properties and content.
     *
     * @param content a copy of the stored content, which the caller may modify; empty for a directory
     */
    public record BlobContent(BlobEntry properties, byte[] content) {
    }

    /**
     * Whether floci-az keeps deleted blobs recoverable, which the blob indexers' native soft-delete deletion
     * detection needs. It does not; see the class documentation.
     */
    public boolean softDeleteSupported() {
        return false;
    }

    public boolean containerExists(String accountName, String containerName) {
        return blobService.containerExists(accountName, containerName);
    }

    /**
     * Whether the account has a hierarchical namespace: listed in
     * {@code floci-az.services.blob.hierarchical-namespace-accounts}, or created through ARM with
     * {@code isHnsEnabled}.
     */
    public boolean hierarchicalNamespaceEnabled(String accountName) {
        return blobService.isHierarchicalNamespaceEnabled(accountName);
    }

    /**
     * Lists the container flat: every blob and stored Data Lake path whose name starts with {@code prefix}.
     *
     * @param prefix a name prefix such as a virtual folder ({@code "folder/"}); {@code null} or empty lists all
     * @return empty when the container does not exist
     */
    public Optional<BlobListing> list(String accountName, String containerName, String prefix) {
        Optional<List<StoredObject>> stored =
                blobService.storedBlobs(accountName, containerName, prefix == null ? "" : prefix);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        boolean hierarchicalNamespace = hierarchicalNamespaceEnabled(accountName);
        List<BlobEntry> entries = stored.get().stream()
                .map(object -> entry(object, hierarchicalNamespace))
                .sorted(Comparator.comparing(BlobEntry::name))
                .toList();
        return Optional.of(new BlobListing(accountName, containerName, hierarchicalNamespace, entries));
    }

    /** The properties of one blob or stored Data Lake path; empty when there is none. */
    public Optional<BlobEntry> properties(String accountName, String containerName, String blobName) {
        boolean hierarchicalNamespace = hierarchicalNamespaceEnabled(accountName);
        return blobService.storedBlob(accountName, containerName, blobName)
                .map(object -> entry(object, hierarchicalNamespace));
    }

    /**
     * The properties and content of one blob or stored Data Lake path, read from one stored record so they always
     * belong together; empty when there is none.
     */
    public Optional<BlobContent> read(String accountName, String containerName, String blobName) {
        boolean hierarchicalNamespace = hierarchicalNamespaceEnabled(accountName);
        return blobService.storedBlob(accountName, containerName, blobName)
                .map(object -> new BlobContent(entry(object, hierarchicalNamespace), object.data().clone()));
    }

    private static BlobEntry entry(StoredObject object, boolean hierarchicalNamespace) {
        Map<String, String> stored = object.metadata();
        Map<String, String> metadata = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        metadata.putAll(BlobServiceHandler.userMetadata(stored));
        boolean directory = "directory".equals(stored.get(DataLakePathOperations.RESOURCE_TYPE))
                || hierarchicalNamespace && "true".equalsIgnoreCase(metadata.get(HDI_ISFOLDER));
        return new BlobEntry(
                stored.getOrDefault(NAME_KEY, object.key()),
                directory,
                object.data().length,
                BlobServiceHandler.usableContentType(stored.get(CONTENT_TYPE_KEY)),
                object.lastModified(),
                object.etag(),
                stored.get(CONTENT_MD5_KEY),
                Collections.unmodifiableMap(metadata));
    }
}
