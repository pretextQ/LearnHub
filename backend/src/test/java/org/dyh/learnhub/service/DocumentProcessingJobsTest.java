package org.dyh.learnhub.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentProcessingJobsTest {
    @TempDir Path work;
    Path directory;
    FileStorageService files;
    VectorIndexService vectors;
    DocumentProcessingJobs jobs;
    @BeforeEach void setup() {
        directory=work.resolve("document-jobs");
        files=mock(FileStorageService.class); vectors=mock(VectorIndexService.class);
        when(vectors.reindexChanged()).thenReturn(Map.of("configured",false,"reason","未配置嵌入，关键词与结构分块可用"));
        jobs=new DocumentProcessingJobs(files,vectors,directory,2);
    }
    @AfterEach void cleanup() { jobs.close(); }
    private Map<String,Object> completed(String id,String kind) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        Map<String,Object> state;
        do {
            state=jobs.status(id,kind);
            if (!List.of("queued","running").contains(state.get("status"))) return state;
            Thread.sleep(10);
        } while(System.nanoTime()<deadline);
        throw new AssertionError("synthetic job did not finish");
    }

    @Test void duplicateMigrationRejoinsAndAFileFailureDoesNotDiscardOtherResults() throws Exception {
        var started=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(files.markdownCandidates(false,true)).thenReturn(List.of(11L,12L));
        when(files.reextract(11L,true)).thenAnswer(call -> {
            started.countDown(); assertTrue(release.await(2,TimeUnit.SECONDS));
            return Map.of("textStatus","ok","extraction",Map.of("report",Map.of("partial",false)));
        });
        when(files.reextract(12L,true)).thenThrow(new IllegalStateException("合成转换失败，旧版本保留"));
        String id=jobs.startMigration(false,true).get("id").toString();
        assertTrue(started.await(2,TimeUnit.SECONDS));
        assertEquals(id,jobs.startMigration(false,true).get("id")); release.countDown();
        var state=completed(id,"migration");
        assertEquals("partial",state.get("status")); assertEquals(2,state.get("done")); assertEquals(1,state.get("failed"));
        verify(vectors,times(1)).reindexChanged();
        var index=(Map<?,?>)state.get("index"); assertEquals(false,index.get("configured"));
        jobs.close(); jobs=new DocumentProcessingJobs(files,vectors,directory,2);
        assertEquals("partial",jobs.status(id,"migration").get("status"),"progress survives a process restart");
    }

    @Test void alreadyConvertedMediaUsesRetainedRegionsAndReportsPendingWork() throws Exception {
        when(files.markdownCandidates(false,true)).thenReturn(List.of(8L)); when(files.isCurrentMarkdown(8L)).thenReturn(true);
        when(files.processMedia(8L,false)).thenReturn(Map.of("textStatus","ok","extraction",Map.of("report",Map.of("partial",true))));
        String id=jobs.startMigration(false,true).get("id").toString();
        assertEquals("partial",completed(id,"migration").get("status"));
        verify(files).processMedia(8L,false); verify(files,never()).reextract(anyLong(),anyBoolean());
    }

    @Test void explicitJobsContinuePendingRegionsAndReuseTheSuccessfulResults() throws Exception {
        when(files.markdownCandidates(false,true)).thenReturn(List.of(8L)); when(files.isCurrentMarkdown(8L)).thenReturn(true);
        var partial=Map.<String,Object>of("textStatus","ok","extraction",Map.of("report",Map.of("partial",true)));
        var finished=Map.<String,Object>of("textStatus","ok","extraction",Map.of("report",Map.of("partial",false)));
        when(files.processMedia(8L,false)).thenReturn(partial,partial,finished);
        when(files.pendingMediaRegions(8L)).thenReturn(25,13,0);
        String id=jobs.startMigration(false,true).get("id").toString();
        assertEquals("completed",completed(id,"migration").get("status"));
        verify(files,times(3)).processMedia(8L,false); verify(vectors,times(1)).reindexChanged();
    }

    @Test void continuationStopsIfTheRoundCannotReducePendingWork() throws Exception {
        when(files.processMedia(8L,false)).thenReturn(Map.of("textStatus","ok","extraction",Map.of("report",Map.of("partial",true))));
        when(files.pendingMediaRegions(8L)).thenReturn(2);
        String id=jobs.startMedia(8L,false).get("id").toString();
        assertEquals("partial",completed(id,"media").get("status"));
        verify(files,times(2)).processMedia(8L,false);
    }

    @Test void staleRunningRecordIsInterruptedAndNeverSilentlyResumesModelCalls() throws Exception {
        String id="11111111-1111-1111-1111-111111111111";
        Files.createDirectories(directory);
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(directory.resolve(id+".json").toFile(),
                Map.of("id",id,"kind","media","status","running","done",0,"total",1));
        assertEquals("failed",jobs.status(id,"media").get("status"));
        verifyNoInteractions(files,vectors);
        assertThrows(IllegalArgumentException.class,()->jobs.status("../invalid","media"));
    }
    @Test void startupRetainsRecentProgressAndOnlyPrunesJobRecords() throws Exception {
        Files.createDirectories(directory);
        String oldest = "11111111-1111-1111-1111-111111111111";
        String interrupted = "22222222-2222-2222-2222-222222222222";
        String newest = "33333333-3333-3333-3333-333333333333";
        writeRecord(oldest, "completed", 1000L);
        writeRecord(interrupted, "running", 2000L);
        writeRecord(newest, "partial", 3000L);
        Files.writeString(directory.resolve("unrelated.json"), "synthetic unrelated file");

        jobs.retireOldRecords();

        assertFalse(Files.exists(directory.resolve(oldest + ".json")));
        assertThrows(IllegalArgumentException.class, () -> jobs.status(oldest, "media"));
        assertEquals("failed", jobs.status(interrupted, "media").get("status"));
        assertEquals("partial", jobs.status(newest, "media").get("status"));
        assertTrue(Files.exists(directory.resolve("unrelated.json")));
        verifyNoInteractions(files, vectors);
    }

    @Test void fullQueueStillRejectsAndHistoryPruningLeavesEveryLiveJobPollable() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ok = Map.<String,Object>of("textStatus", "ok");
        when(files.processMedia(anyLong(), eq(false))).thenReturn(ok);
        when(files.processMedia(eq(1L), eq(false))).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return ok;
        });

        String running = (String) jobs.startMedia(1L, false).get("id");
        List<String> queued = new ArrayList<>();
        String last;
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (long id = 2; id <= 17; id++) queued.add((String) jobs.startMedia(id, false).get("id"));
            var expired = jobs.startMedia(18L, false);
            assertEquals("failed", expired.get("status"));
            assertEquals("failed", jobs.startMedia(19L, false).get("status"));
            assertEquals("failed", jobs.startMedia(20L, false).get("status"));
            assertThrows(IllegalArgumentException.class, () -> jobs.status((String) expired.get("id"), "media"));
            assertEquals("running", jobs.status(running, "media").get("status"));
            for (String id : queued) {
                assertEquals("queued", jobs.status(id, "media").get("status"));
                assertTrue(Files.exists(directory.resolve(id + ".json")));
            }
            last = queued.getLast();
        } finally {
            release.countDown();
        }
        assertEquals("completed", completed(last, "media").get("status"));
        assertThrows(IllegalArgumentException.class, () -> jobs.status(running, "media"));
        try (var records = Files.list(directory)) {
            assertEquals(2, records.filter(path -> path.getFileName().toString().endsWith(".json")).count());
        }
    }

    private void writeRecord(String id, String status, long modified) throws Exception {
        Path path = directory.resolve(id + ".json");
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(path.toFile(),
                Map.of("id", id, "kind", "media", "status", status));
        Files.setLastModifiedTime(path, FileTime.fromMillis(modified));
    }

}
