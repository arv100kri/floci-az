package io.floci.az.services.search.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Runs a skill's calls to its endpoint with at most {@code degreeOfParallelism} in flight, each on a virtual thread.
 * Results come back in call order, so what the pipeline does with them does not depend on timing.
 */
final class ParallelCalls {

    private ParallelCalls() {
    }

    /**
     * Runs {@code work} once per item.
     *
     * @param items       the work items, such as batches of records
     * @param parallelism how many items may run at once
     * @param work        the work for one item; it reports its own failures in its result
     * @param onFailure   the result of an item whose work failed unexpectedly, given the reason
     * @return each item's result, in item order
     */
    static <T, R> List<R> run(List<T> items, int parallelism, Function<T, R> work, Function<String, R> onFailure) {
        List<R> results = new ArrayList<>(items.size());
        if (items.size() <= 1 || parallelism <= 1) {
            for (T item : items) {
                results.add(work.apply(item));
            }
            return results;
        }
        Semaphore permits = new Semaphore(parallelism);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<R>> futures = new ArrayList<>(items.size());
            for (T item : items) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        return work.apply(item);
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (Future<R> future : futures) {
                results.add(await(future, onFailure));
            }
        }
        return results;
    }

    private static <R> R await(Future<R> future, Function<String, R> onFailure) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return onFailure.apply("the call was interrupted");
        } catch (ExecutionException e) {
            return onFailure.apply(WebApiSkill.describe(e.getCause()));
        }
    }
}
