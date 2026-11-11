package org.dyh.learnhub.service;

import org.dyh.learnhub.common.FileTextExtractedEvent;
import org.dyh.learnhub.common.KnowledgeChangedEvent;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileUploadTransactionsTest {
    private static final String CONTENT = "# Synthetic document\n\nSample paragraph.\n";
    @TempDir Path workspace;
    private String previousDirectory;
    private final List<String> phases = new ArrayList<>();
    private final List<Object> committedEvents = new ArrayList<>();
    private final FileInfoMapper files = mock(FileInfoMapper.class);
    private final DocumentTextService parser = spy(new DocumentTextService());
    private final DocumentExtractionStore reports = spy(new DocumentExtractionStore());
    private final FileStorageService service = new FileStorageService(files, null, parser, null, null, null,
            (ApplicationEventPublisher) event -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { committedEvents.add(event); }
                });
            }, mock(LearningActivityService.class), null, mock(FolderService.class));

    @BeforeEach void setUp() throws IOException {
        previousDirectory = System.getProperty("user.dir");
        System.setProperty("user.dir", workspace.toString());
        service.setUploadTransactionManager(new RecordingTransactions());
        service.setExtractionStore(reports);
        when(files.insert(any(FileInfo.class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            FileInfo row = call.getArgument(0);
            assertThat(row.getTextStatus()).isEqualTo("pending");
            assertThat(Files.isRegularFile(workspace.resolve("uploads").resolve(row.getStoreName()))).isTrue();
            row.setId(7L);
            phases.add("insert");
            return 1;
        });
        when(files.updateById(any(FileInfo.class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            phases.add("update");
            return 1;
        });
        var latest = new FileInfo();
        latest.setOriginName("renamed.md");
        latest.setCategoryId(9L);
        when(files.selectOne(any())).thenReturn(latest);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(phases).containsExactly("begin", "insert", "commit");
            phases.add("extract");
            return call.callRealMethod();
        }).when(parser).extractDetailed(any(Path.class), eq("md"), anyLong());
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            phases.add("report");
            return call.callRealMethod();
        }).when(reports).save(anyLong(), any(Path.class), any());
    }

    @AfterEach void restoreDirectory() {
        System.setProperty("user.dir", previousDirectory);
    }

    @Test void commitsPendingFileBeforeParsingAndPublishesOnlyWithCommittedExtraction() {
        FileInfo result = upload();
        assertThat(phases).containsExactly("begin", "insert", "commit", "extract", "report", "begin", "update", "commit");
        assertThat(result.getTextStatus()).isEqualTo("ok");
        assertThat(result.getTextContent()).contains("Sample paragraph");
        Path source = workspace.resolve("uploads").resolve(result.getStoreName());
        assertThat(DocumentExtractionStore.reportPath(source, 7L, result.getTextContent())).exists();
        assertThat(committedEvents).containsExactly(new FileTextExtractedEvent(7L, "renamed.md", result.getTextContent()),
                new KnowledgeChangedEvent(Set.of(9L), "file"));
    }

    @Test void reportFailureKeepsTheOriginalAndDoesNotPublishTrainingText() throws IOException {
        doThrow(new IOException("synthetic write failure")).when(reports).save(anyLong(), any(Path.class), any());
        FileInfo result = upload();
        assertThat(result.getTextStatus()).isEqualTo("failed");
        assertThat(result.getTextError()).contains("定位报告保存失败");
        assertThat(result.getTextContent()).isNull();
        assertThat(workspace.resolve("uploads").resolve(result.getStoreName())).hasContent(CONTENT.trim());
        assertThat(committedEvents).containsExactly(new KnowledgeChangedEvent(Set.of(9L), "file"));
    }

    @Test void deletedPendingFileDoesNotPublishEventsForAnUnstoredExtraction() {
        when(files.updateById(any(FileInfo.class))).thenReturn(0);
        assertThatThrownBy(this::upload).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已删除");
        assertThat(phases).endsWith("begin", "rollback");
        assertThat(committedEvents).isEmpty();
    }

    private FileInfo upload() {
        return service.uploadBytes("sample.md", CONTENT.getBytes(StandardCharsets.UTF_8), 8L);
    }

    private final class RecordingTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) { phases.add("begin"); }
        @Override protected void doCommit(DefaultTransactionStatus tx) { phases.add("commit"); }
        @Override protected void doRollback(DefaultTransactionStatus tx) { phases.add("rollback"); }
    }
}
