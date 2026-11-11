package org.dyh.learnhub.common;

/** A versioned save lost the race for this note. */
public class NoteVersionConflictException extends RuntimeException {
    public NoteVersionConflictException() {
        super("库中的笔记已更新，请加载最新版本；本次未保存");
    }
}
