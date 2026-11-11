package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class JobHistoryTest {
    @Test void queuedAndRunningJobsSurviveEvictionAndFinishInCompletionOrder() {
        var jobs = new JobHistory<String>(2);
        var queue = new ArrayDeque<Runnable>();
        for (String id : List.of("running", "old", "recent", "latest")) {
            jobs.submit(id, id, queue::add, () -> {}, () -> {});
        }

        Runnable running = queue.removeFirst();
        while (!queue.isEmpty()) queue.removeFirst().run();

        assertEquals(List.of("running"), jobs.activeJobs());
        assertEquals("running", jobs.get("running"));
        assertNull(jobs.get("old"));
        assertEquals("recent", jobs.get("recent"));
        assertEquals("latest", jobs.get("latest"));

        running.run();
        assertTrue(jobs.activeJobs().isEmpty());
        assertNull(jobs.get("recent"));
        assertEquals("running", jobs.get("running"));
    }

    @Test void failedExecutionRetiresTheRecordAndRejectedSubmissionLeavesNoActiveJob() {
        var jobs = new JobHistory<StringBuilder>(1);
        var job = new StringBuilder("running");
        assertThrows(AssertionError.class, () -> jobs.submit("aborted", job, Runnable::run,
                () -> { throw new AssertionError("synthetic abort"); },
                () -> job.replace(0, job.length(), "failed")));
        assertEquals("failed", jobs.get("aborted").toString());
        assertTrue(jobs.activeJobs().isEmpty());

        assertThrows(RejectedExecutionException.class, () -> jobs.submit("rejected", new StringBuilder(),
                task -> { throw new RejectedExecutionException(); }, () -> {}, () -> {}));
        assertNull(jobs.get("rejected"));
        assertTrue(jobs.activeJobs().isEmpty());
    }
}
