package io.floci.az.services.search.dataplane;

/**
 * A kind of search resource that reports a usage counter in Get Service Statistics ({@code /servicestats}). Each
 * resource controller implements it; the statistics controller collects every implementation.
 */
public interface SearchResourceCounter {

    /** The counter's name in {@code counters}: {@code synonymMaps}, {@code aliasesCount}, ... */
    String counterName();

    /** How many resources of this kind the service holds. */
    long usage(String service);
}
