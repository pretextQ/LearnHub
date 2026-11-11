package org.dyh.learnhub.service;

import org.dyh.learnhub.mapper.KbChunkMapper;
import org.dyh.learnhub.mapper.WikiPageMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KgPipelineJobTest {
    @Test void concurrentStartsJoinOneBuildAndFailureAllowsAnotherBuild() throws Exception {
        var sources = mock(KbChunkMapper.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(sources.allNotes()).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test did not release build");
            throw new IllegalStateException("synthetic build failure");
        }).thenReturn(List.of());
        var service = new KgPipelineService(sources, mock(WikiPageMapper.class), mock(KgGraphService.class),
                mock(TripleExtractor.class), mock(KgCommunityService.class));

        String first = (String) service.start().get("jobId");
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var callersReady = new CountDownLatch(1);
            try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Map<String, Object>>> starts = new ArrayList<>();
                for (int i = 0; i < 8; i++) starts.add(callers.submit(() -> {
                    if (!callersReady.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test did not start callers");
                    return service.start();
                }));
                callersReady.countDown();
                for (var start : starts) assertEquals(first, start.get(5, TimeUnit.SECONDS).get("jobId"));
            }
            assertTrue(service.hasRunningJobs());
            verify(sources).allNotes();
        } finally {
            release.countDown();
        }
        awaitStatus(service, first, "failed");
        String second = (String) service.start().get("jobId");
        assertNotEquals(first, second);
        awaitStatus(service, second, "done");
        verify(sources, times(2)).allNotes();
        assertFalse(service.hasRunningJobs());
    }

    private static void awaitStatus(KgPipelineService service, String id, String status) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ("running".equals(service.view(id).get("status")) && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(status, service.view(id).get("status"));
    }
}
