package io.floci.az.services.search.indexer;

import io.floci.az.core.Resettable;
import io.floci.az.services.search.SearchServiceRegistry;
import io.floci.az.services.search.SearchServiceRegistry.SearchServiceInfo;
import io.floci.az.services.search.indexer.IndexerState.RunStart;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs indexers in the background and on their schedules, one run per indexer at a time, as Learn's "Run or reset
 * indexers" and "Schedule indexers" describe: an on-demand run while one is in progress is refused, a scheduled start
 * that finds the previous run still going is skipped ("the pending execution is postponed until the next scheduled
 * occurrence"), scheduled starts fall on {@code startTime} plus a whole number of intervals, and re-enabling a disabled
 * indexer counts the intervals from the moment it is re-enabled. After a run ends as a {@code persistentFailure} the
 * schedule stops starting runs until the indexer is updated or reset.
 *
 * <p>Schedules are re-armed at startup from the stored indexers. {@link #clear()} (the admin reset) cancels every
 * schedule, stops the runs in progress at their next item and forgets every data source, indexer and state; shutdown
 * stops the threads.</p>
 */
@ApplicationScoped
public class IndexerScheduler implements Resettable {

    private static final Logger LOG = Logger.getLogger(IndexerScheduler.class);
    private static final Duration STOP_WAIT = Duration.ofSeconds(10);
    private static final int WORKERS = 4;

    private final IndexerStore store;
    private final IndexerRunner runner;
    private final SearchServiceRegistry services;
    private final ScheduledExecutorService timer;
    private final ExecutorService workers;
    private final Map<String, Future<?>> schedules = new ConcurrentHashMap<>();
    private final Map<String, Instant> baselines = new ConcurrentHashMap<>();
    private final Map<String, RunHandle> running = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean stopping;

    /** A run in progress, which a reset or a deletion cancels. */
    private static final class RunHandle {

        private final long generation;
        private volatile boolean cancelled;

        private RunHandle(long generation) {
            this.generation = generation;
        }
    }

    @Inject
    public IndexerScheduler(IndexerStore store, IndexerRunner runner, SearchServiceRegistry services) {
        this.store = store;
        this.runner = runner;
        this.services = services;
        this.timer = Executors.newSingleThreadScheduledExecutor(daemonThreads("search-indexer-schedule"));
        this.workers = Executors.newFixedThreadPool(WORKERS, daemonThreads("search-indexer-run"));
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    void onStart(@Observes StartupEvent event) {
        for (Map.Entry<String, IndexerDefinition> indexer : store.allIndexers()) {
            schedule(indexer.getKey(), indexer.getValue(), false);
        }
    }

    /**
     * Starts a run of an indexer in the background.
     *
     * @return false when a run of the indexer is already in progress (or the indexer or its service is gone)
     */
    public boolean start(String service, String name) {
        Optional<IndexerDefinition> definition = store.indexer(service, name);
        Optional<SearchServiceInfo> info = services.find(service);
        if (definition.isEmpty() || info.isEmpty() || stopping) {
            return false;
        }
        IndexerState state = store.state(service, name);
        RunStart runStart;
        RunHandle handle = new RunHandle(generation.get());
        synchronized (state) {
            if (state.running()) {
                return false;
            }
            runStart = state.begin(Instant.now());
            running.put(key(service, name), handle);
        }
        try {
            workers.submit(() -> execute(service, name, definition.get(), info.get(), state, runStart, handle));
        } catch (RejectedExecutionException e) {
            LOG.debugv("Indexer {0}/{1} not started: the scheduler is stopping", service, name);
            state.interrupted(Instant.now());
            running.remove(key(service, name), handle);
        }
        return true;
    }

    private void execute(String service, String name, IndexerDefinition definition, SearchServiceInfo info,
                         IndexerState state, RunStart runStart, RunHandle handle) {
        try {
            LOG.debugv("Running indexer {0}/{1}", service, name);
            IndexerRun.Outcome outcome = runner.run(info, definition, runStart,
                () -> handle.cancelled || stopping || generation.get() != handle.generation, state::progress);
            if (generation.get() != handle.generation) {
                return;
            }
            state.finish(outcome.result(), runStart, outcome.tracking(), outcome.persistent());
            store.saveState(service, name, state);
            LOG.debugv("Indexer {0}/{1} finished: {2}, {3} items, {4} failed", service, name,
                outcome.result().status(), outcome.result().itemsProcessed(), outcome.result().itemsFailed());
        } catch (RuntimeException e) {
            LOG.errorv(e, "Indexer {0}/{1} failed unexpectedly", service, name);
            state.interrupted(Instant.now());
        } finally {
            running.remove(key(service, name), handle);
        }
    }

    /**
     * Arms the schedule of an indexer, replacing any earlier one; does nothing for a disabled or unscheduled indexer.
     *
     * @param reenabled whether the indexer was just re-enabled, which counts its intervals from now
     */
    public void schedule(String service, IndexerDefinition definition, boolean reenabled) {
        String key = key(service, definition.name());
        cancelSchedule(key);
        if (definition.disabled() || definition.schedule() == null || stopping) {
            baselines.remove(key);
            return;
        }
        Instant now = Instant.now();
        if (reenabled) {
            baselines.put(key, now);
        }
        Instant baseline = baselines.getOrDefault(key, definition.schedule().startTime());
        arm(service, definition.name(), definition.schedule().nextRunAfter(baseline, now.plusMillis(1)));
    }

    /** Forgets the schedule baseline of an indexer whose schedule changed, so it counts from the new start time. */
    public void resetBaseline(String service, String name) {
        baselines.remove(key(service, name));
    }

    private void arm(String service, String name, Instant at) {
        long delay = Math.max(0, Duration.between(Instant.now(), at).toMillis());
        try {
            schedules.put(key(service, name), timer.schedule(() -> fire(service, name), delay,
                TimeUnit.MILLISECONDS));
        } catch (RejectedExecutionException e) {
            LOG.debugv("Indexer {0}/{1} not scheduled: the scheduler is stopping", service, name);
        }
    }

    private void fire(String service, String name) {
        Optional<IndexerDefinition> definition = store.indexer(service, name);
        if (definition.isEmpty() || definition.get().disabled() || definition.get().schedule() == null) {
            return;
        }
        if (!store.state(service, name).inError() && !start(service, name)) {
            LOG.debugv("Scheduled run of indexer {0}/{1} skipped: a run is still in progress", service, name);
        }
        String key = key(service, name);
        Instant baseline = baselines.getOrDefault(key, definition.get().schedule().startTime());
        arm(service, name, definition.get().schedule().nextRunAfter(baseline, Instant.now().plusMillis(1)));
    }

    /** Stops scheduling an indexer and cancels its run in progress, for a deleted indexer. */
    public void forget(String service, String name) {
        String key = key(service, name);
        cancelSchedule(key);
        baselines.remove(key);
        RunHandle handle = running.get(key);
        if (handle != null) {
            handle.cancelled = true;
        }
    }

    /** Whether a run of the indexer is in progress. */
    public boolean running(String service, String name) {
        return running.containsKey(key(service, name));
    }

    private void cancelSchedule(String key) {
        Future<?> previous = schedules.remove(key);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    @Override
    public void clear() {
        generation.incrementAndGet();
        List.copyOf(schedules.keySet()).forEach(this::cancelSchedule);
        baselines.clear();
        awaitRuns();
        store.clear();
    }

    private void awaitRuns() {
        Instant deadline = Instant.now().plus(STOP_WAIT);
        while (!running.isEmpty() && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!running.isEmpty()) {
            LOG.warnv("{0} indexer runs did not stop within {1}", running.size(), STOP_WAIT);
        }
    }

    @PreDestroy
    void stop() {
        stopping = true;
        timer.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static String key(String service, String name) {
        return service.toLowerCase(Locale.ROOT) + "/" + name;
    }
}
