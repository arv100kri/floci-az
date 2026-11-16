package io.floci.az.services.search.dataplane;

import io.floci.az.services.search.engine.SearchEngine;
import io.floci.az.services.search.engine.index.LiveIndex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Resolves the index a document operation names ({@code indexes('x')/docs...}): an index of that name, else the
 * index an alias of that name points to, else a 404.
 */
@ApplicationScoped
public class DocumentIndexResolver {

    private final SearchEngine engine;
    private final AliasesController aliases;

    @Inject
    public DocumentIndexResolver(SearchEngine engine, AliasesController aliases) {
        this.engine = engine;
        this.aliases = aliases;
    }

    public LiveIndex resolve(SearchCall call) {
        String name = call.path("index");
        Optional<LiveIndex> index = engine.service(call.serviceName()).find(name);
        if (index.isPresent()) {
            return index.get();
        }
        return aliases.resolveIndex(call.serviceName(), name)
            .flatMap(target -> engine.service(call.serviceName()).find(target))
            .orElseThrow(() -> IndexesController.notFound(call, name));
    }
}
