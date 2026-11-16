package io.floci.az.services.search.indexer;

import io.floci.az.services.blob.BlobReader;
import io.floci.az.services.blob.BlobReader.BlobEntry;
import io.floci.az.services.blob.BlobReader.BlobListing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The items of a blob or ADLS Gen2 data source: the blobs of its container under the {@code container.query} virtual
 * folder, oldest change first, ADLS Gen2 directories left out. Change detection is built in: a blob is new when its
 * last-modified time is past the high-water mark. Blobs whose extension {@code indexedFileNameExtensions} does not list,
 * or {@code excludedFileNameExtensions} lists (it wins when both do), and blobs with {@code AzureSearch_Skip} set to
 * {@code true}, are tracked but not indexed.
 */
final class BlobSource implements SourceReader {

    private final BlobReader blobs;
    private final String account;
    private final String container;
    private final String prefix;
    private final IndexerParameters parameters;
    private final BlobCracker cracker;

    BlobSource(BlobReader blobs, String account, String container, String folder, IndexerParameters parameters,
               BlobCracker cracker) {
        this.blobs = blobs;
        this.account = account;
        this.container = container;
        this.prefix = folderPrefix(folder);
        this.parameters = parameters;
        this.cracker = cracker;
    }

    /** A {@code container.query} as a name prefix: {@code json} and {@code /json/} both select {@code json/...}. */
    static String folderPrefix(String folder) {
        if (folder == null || folder.isBlank()) {
            return null;
        }
        String trimmed = folder.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    @Override
    public List<SourceItem> items(ChangeTracking tracking, boolean everything) throws IndexerRunException {
        Optional<BlobListing> listing = blobs.list(account, container, prefix);
        if (listing.isEmpty()) {
            throw new IndexerRunException("Error with data source: The blob container '" + container + "' does not "
                + "exist in the storage account '" + account + "'.");
        }
        Set<String> indexed = parameters.indexedFileNameExtensions();
        Set<String> excluded = parameters.excludedFileNameExtensions();
        List<SourceItem> items = new ArrayList<>();
        for (BlobEntry entry : listing.get().entries()) {
            if (entry.directory()) {
                continue;
            }
            String extension = BlobCracker.extension(entry.name());
            boolean skip = !indexed.isEmpty() && !indexed.contains(extension) || excluded.contains(extension)
                || "true".equalsIgnoreCase(entry.metadata().get(BlobCracker.SKIP));
            String path = cracker.storagePath(entry.name());
            SourceItem item = new SourceItem(entry.name(), entry.etag(), ChangeTracking.nanos(entry.lastModified()),
                path, path, skip, () -> cracker.crack(entry));
            if (everything || tracking.admits(item.trackingValue(), item.trackingIdentity())) {
                items.add(item);
            }
        }
        items.sort(Comparator.comparing(SourceItem::trackingValue).thenComparing(SourceItem::identity));
        return items;
    }

    @Override
    public boolean timestampTracking() {
        return true;
    }
}
