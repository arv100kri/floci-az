package io.floci.az.services.search.security;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Whose documents a read of an index may return when the index enforces document-level permission filters
 * ({@code permissionFilterOption: enabled}): every document, for an elevated read; else the documents whose
 * {@code userIds} or {@code groupIds} grant one of {@link #principalIds()}, or that grant {@code all}.
 *
 * <p>{@link SearchAuthorizer} decides it from the request's {@code x-ms-query-source-authorization} and
 * {@code x-ms-enable-elevated-read} headers, and {@code SearchHandler} binds it for the duration of the controller
 * call, so the search pipeline and the lookup, count, suggest and autocomplete hooks read it with {@link #current()}
 * without it passing through every signature. Outside such a call (an engine unit test, work floci-az does on its
 * own) the access is {@link #ANONYMOUS}: only documents granted to {@code all} are visible, as for a request that
 * names no end user.</p>
 *
 * @param unrestricted whether every document is visible: an elevated read the caller may make
 * @param principalIds the end user's object id and the ids of the groups it belongs to; empty for no end user
 */
public record DocumentAccess(boolean unrestricted, Set<String> principalIds) {

    /** Every document is visible. */
    public static final DocumentAccess UNRESTRICTED = new DocumentAccess(true, Set.of());

    /** No end user: only the documents granted to {@code all} are visible. */
    public static final DocumentAccess ANONYMOUS = new DocumentAccess(false, Set.of());

    private static final ScopedValue<DocumentAccess> CURRENT = ScopedValue.newInstance();

    public DocumentAccess {
        principalIds = Set.copyOf(principalIds);
    }

    /** The access of an end user known by {@code principalIds}. */
    public static DocumentAccess of(Set<String> principalIds) {
        return new DocumentAccess(false, principalIds);
    }

    /** The access bound to the running request, or {@link #ANONYMOUS} outside one. */
    public static DocumentAccess current() {
        return CURRENT.orElse(ANONYMOUS);
    }

    /** Runs {@code work} with this access bound as {@link #current()}. */
    public <T> T bindDuring(Supplier<T> work) {
        return ScopedValue.where(CURRENT, this).call(work::get);
    }
}
