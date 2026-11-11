package org.dyh.learnhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Explicit, resumable-by-retry migrations. Reading a document never starts a model or conversion job. */
@Service
public class DocumentProcessingJobs {
    private final FileStorageService files;
    private final VectorIndexService vectors;
    private final ObjectMapper json = new ObjectMapper();
    private static final String JOB_ID = "[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}";
    private final Path jobDirectory;
    private final int historyLimit;
    private final JobHistory<Job> jobs;
    private final Object jobFilesLock = new Object();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),task -> {
        Thread thread = new Thread(task,"document-markdown-jobs"); thread.setDaemon(true); return thread;
    });

    @Autowired
    public DocumentProcessingJobs(FileStorageService files, VectorIndexService vectors) {
        this(files, vectors, Path.of(System.getProperty("user.dir"), "uploads", ".derived", "document-jobs"),
                JobHistory.DEFAULT_LIMIT);
    }

    DocumentProcessingJobs(FileStorageService files, VectorIndexService vectors, Path jobDirectory, int historyLimit) {
        this.files = files;
        this.vectors = vectors;
        this.jobDirectory = jobDirectory;
        this.historyLimit = historyLimit;
        this.jobs = new JobHistory<>(historyLimit);
    }

    @PostConstruct
    void retireOldRecords() {
        pruneJobFiles();
    }

    private static final class Job {
        final String id=UUID.randomUUID().toString(), kind, key;
        String status="queued", stage="等待处理", error;
        int done,total,failed,partial;
        long updatedAt=System.currentTimeMillis();
        Map<String,Object> index=Map.of();
        final List<Map<String,Object>> items=new ArrayList<>();
        Job(String kind,String key) { this.kind=kind; this.key=key; }
        synchronized Map<String,Object> view() {
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("id",id); result.put("kind",kind); result.put("status",status); result.put("done",done); result.put("total",total);
            result.put("failed",failed); result.put("partial",partial); result.put("stage",stage); result.put("error",error);
            result.put("index",index); result.put("items",List.copyOf(items)); result.put("updatedAt",updatedAt); return result;
        }
        synchronized boolean active() { return status.equals("queued") || status.equals("running"); }
    }

    public synchronized Map<String,Object> startMedia(Long id,boolean force) {
        files.detail(id);
        String key="media:"+id;
        Job prior=jobs.activeJobs().stream().filter(job -> job.key.equals(key) && job.active()).findFirst().orElse(null);
        if (prior!=null) return prior.view();
        Job job=new Job("media",key); job.total=1;
        submit(job,() -> run(job,List.of(id),force,true,true)); return job.view();
    }

    public synchronized Map<String,Object> startMigration(boolean force,boolean processMedia) {
        Job prior=jobs.activeJobs().stream().filter(job -> job.key.equals("migration") && job.active()).findFirst().orElse(null);
        if (prior!=null) return prior.view();
        Job job=new Job("migration","migration");
        submit(job,() -> {
            try { run(job,files.markdownCandidates(force,processMedia),force,processMedia,false); }
            catch (Exception failed) { fail(job,"无法读取待转换资料，请稍后重试"); }
        }); return job.view();
    }

    private void submit(Job job,Runnable task) {
        jobs.register(job.id, job);
        persist(job);
        try {
            worker.execute(() -> {
                try { task.run(); }
                finally {
                    if (job.active()) fail(job, "任务意外中断，已完成的资料保留");
                }
            });
        } catch (RejectedExecutionException busy) {
            fail(job, "文档处理队列已满，请稍后重试");
        }
    }

    private void run(Job job,List<Long> ids,boolean force,boolean processMedia,boolean mediaOnly) {
        synchronized(job) { job.status="running"; job.total=ids.size(); job.stage="转换 Markdown 与处理媒体"; }
        persist(job);
        for (Long id:ids) {
            if (Thread.currentThread().isInterrupted()) { fail(job,"任务已中断，已完成的资料保留；重试会跳过已转换版本"); return; }
            Map<String,Object> item=new LinkedHashMap<>(); item.put("id",id);
            try {
                Map<String,Object> detail=(mediaOnly || !force && files.isCurrentMarkdown(id) && processMedia)
                        ? files.processMedia(id,force) : files.reextract(id,processMedia);
                // Explicit background work continues bounded rounds; successful regions stay cached.
                // Do not repeatedly guess uncertain/failed results, or loop when a round makes no progress.
                int pending=processMedia?files.pendingMediaRegions(id):0;
                for(int round=0; pending>0 && round<100 && !Thread.currentThread().isInterrupted(); round++) {
                    synchronized(job) { job.stage="继续处理公式与图片，剩余 "+pending+" 个区域"; }
                    persist(job);
                    detail=files.processMedia(id,false);
                    int remaining=files.pendingMediaRegions(id);
                    if(remaining>=pending) break;
                    pending=remaining;
                }
                var summary=json.valueToTree(detail).path("extraction");
                boolean incomplete=pending>0 || summary.path("report").path("partial").asBoolean() || summary.path("report").path("truncated").asBoolean();
                boolean empty=!"ok".equals(String.valueOf(detail.get("textStatus")));
                item.put("status",empty?"failed":incomplete?"partial":"completed");
                if (empty) item.put("error","尚无通过质量检查的检索正文，原件和 Markdown 候选已保留");
                synchronized(job) { if (empty) job.failed++; else if (incomplete) job.partial++; }
            } catch (Exception failed) {
                item.put("status","failed"); item.put("error",safe(failed));
                synchronized(job) { job.failed++; }
            }
            synchronized(job) { job.items.add(Map.copyOf(item)); job.done++; job.stage="已处理 "+job.done+" / "+job.total+" 份资料"; }
            persist(job);
        }
        synchronized(job) { job.stage="更新分块与索引"; } persist(job);
        boolean indexFailed=false;
        try {
            Map<String,Object> indexed=vectors.reindexChanged();
            synchronized(job) { job.index=indexed; }
        } catch (Exception failed) {
            indexFailed=true;
            synchronized(job) { job.index=Map.of("updated",false,"error","向量索引未完成；当前正文与关键词分块已更新，可在知识库重试索引"); }
        }
        synchronized(job) {
            job.status=job.failed>0 || job.partial>0 || indexFailed?"partial":"completed";
            job.stage=job.status.equals("completed")?"Markdown、定位与检索已更新":"转换已结束，部分资料或媒体仍需核对";
            recordCompletion(job);
        }
    }

    private static String safe(Exception failed) {
        String text=failed instanceof IllegalArgumentException || failed instanceof IllegalStateException?failed.getMessage():null;
        if (text==null || text.isBlank()) return "处理失败，原文件保留，可稍后重试";
        return text.replaceAll("[\\r\\n]"," ").substring(0,Math.min(240,text.length()));
    }
    private void fail(Job job, String reason) {
        synchronized (job) {
            if (!job.active()) return;
            job.status = "failed";
            job.stage = "任务未完成";
            job.error = reason;
            recordCompletion(job);
        }
    }

    /** Called while holding the job monitor so a terminal poll can already survive a restart. */
    private void recordCompletion(Job job) {
        synchronized (jobFilesLock) {
            persist(job);
            jobs.finish(job.id);
            pruneJobFiles();
        }
    }

    private void persist(Job job) {
        synchronized (job) {
            job.updatedAt = System.currentTimeMillis();
            synchronized (jobFilesLock) {
                try {
                    Files.createDirectories(jobDirectory);
                    Path temporary = Files.createTempFile(jobDirectory, "job-", ".tmp");
                    try {
                        json.writeValue(temporary.toFile(), job.view());
                        Path target = jobDirectory.resolve(job.id + ".json");
                        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } finally { Files.deleteIfExists(temporary); }
                } catch (IOException ignored) { /* Processing results are versioned independently of progress. */ }
            }
        }
    }

    private record SavedJob(String id, Path path, FileTime modified) {}

    private void pruneJobFiles() {
        synchronized (jobFilesLock) {
            if (!Files.isDirectory(jobDirectory)) return;
            var active = jobs.activeIds();
            var recent = jobs.recentIds();
            var saved = new ArrayList<SavedJob>();
            try (var entries = Files.newDirectoryStream(jobDirectory, "*.json")) {
                for (Path path : entries) {
                    String name = path.getFileName().toString();
                    String id = name.substring(0, name.length() - 5);
                    if (!id.matches(JOB_ID) || active.contains(id) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    saved.add(new SavedJob(id, path, Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)));
                }
                saved.sort(Comparator.comparing((SavedJob entry) -> !recent.contains(entry.id()))
                        .thenComparing(SavedJob::modified, Comparator.reverseOrder()).thenComparing(SavedJob::id));
                for (int i = historyLimit; i < saved.size(); i++) {
                    try { Files.deleteIfExists(saved.get(i).path()); }
                    catch (IOException ignored) { /* Retry when another job finishes. */ }
                }
            } catch (IOException | java.nio.file.DirectoryIteratorException ignored) {
                // Best effort; status reads and document results remain available.
            }
        }
    }

    public Map<String,Object> status(String id,String kind) {
        if (id==null || !id.matches(JOB_ID)) throw new IllegalArgumentException("任务不存在");
        Job live=jobs.get(id);
        if (live!=null && live.kind.equals(kind)) return live.view();
        try {
            Path file=jobDirectory.resolve(id+".json");
            if (!Files.isRegularFile(file) || Files.size(file)>4L*1024*1024) throw new IllegalArgumentException("任务不存在");
            Map<String,Object> saved=json.readValue(Files.readAllBytes(file),new com.fasterxml.jackson.core.type.TypeReference<>(){});
            if (!kind.equals(saved.get("kind"))) throw new IllegalArgumentException("任务不存在");
            if (List.of("queued","running").contains(saved.get("status"))) {
                saved.put("status","failed"); saved.put("error","应用已重启，任务未完成；重试会跳过已完成版本");
            }
            return saved;
        } catch(IllegalArgumentException missing) { throw missing; }
        catch(Exception missing) { throw new IllegalArgumentException("任务不存在或记录无法读取"); }
    }
    @PreDestroy public void close() { worker.shutdownNow(); }
}
