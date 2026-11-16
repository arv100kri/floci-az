package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.model.SearchJson;

/**
 * <b>STUB, to be replaced by facets.</b> Will compute value, interval and range facets over every hit and fill
 * {@link SearchExecution#facets()}. Today it answers each requested facet with no buckets.
 */
public final class FacetStage implements SearchStage {

    @Override
    public void apply(SearchExecution execution) {
        for (String facet : execution.request().facets()) {
            int comma = facet.indexOf(',');
            String field = (comma < 0 ? facet : facet.substring(0, comma)).trim();
            execution.facets().put(field, SearchJson.NODES.arrayNode());
        }
    }
}
