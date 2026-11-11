package org.dyh.learnhub.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Live jobs are never evicted. Completed jobs are kept in completion order for polling. */
final class JobHistory<J> {
    static final int DEFAULT_LIMIT = 64;

    private final int limit;
    private final LinkedHashMap<String, J> active = new LinkedHashMap<>();
    private final LinkedHashMap<String, J> recent = new LinkedHashMap<>();

    JobHistory() { this(DEFAULT_LIMIT); }

    JobHistory(int limit) {
        if (limit < 1) throw new IllegalArgumentException("A job history must retain at least one result");
        this.limit = limit;
    }

    synchronized void register(String id, J job) {
        active.put(id, job);
    }

    synchronized void finish(String id) {
        J job = active.remove(id);
        if (job == null) return;
        recent.put(id, job);
        while (recent.size() > limit) recent.pollFirstEntry();
    }

    synchronized J get(String id) {
        J job = active.get(id);
        return job == null ? recent.get(id) : job;
    }

    synchronized List<J> activeJobs() {
        return List.copyOf(active.values());
    }

    synchronized Set<String> activeIds() {
        return Set.copyOf(active.keySet());
    }

    synchronized Set<String> recentIds() {
        return Set.copyOf(recent.keySet());
    }

    void submit(String id, J job, Executor runner, Runnable task, Runnable ensureTerminal) {
        register(id, job);
        try {
            runner.execute(() -> {
                try {
                    task.run();
                } finally {
                    try { ensureTerminal.run(); }
                    finally { finish(id); }
                }
            });
        } catch (RejectedExecutionException rejected) {
            synchronized (this) { active.remove(id, job); }
            throw rejected;
        }
    }
}
