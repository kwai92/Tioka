package com.tioka.runtime.infrastructure;

import com.tioka.framework.error.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalWorkspaceFsTest {
    @TempDir Path tmp;

    private LocalWorkspaceFs fs() {
        return LocalWorkspaceFs.forTest(tmp, new LocalObjectStore(tmp.resolve("objects").toString()));
    }

    @Test
    void ensureLayoutCreatesDirs() {
        LocalWorkspaceFs fs = fs();
        fs.ensureLayout("ws1", "sess1");
        Path root = fs.root("ws1", "sess1");
        assertTrue(Files.isDirectory(root.resolve("uploads")));
        assertTrue(Files.isDirectory(root.resolve("skills")));
        assertTrue(Files.isDirectory(root.resolve("scratch")));
        assertTrue(Files.isDirectory(root.resolve("artifacts/out")));
    }

    @Test
    void writeAndReadScratch() {
        LocalWorkspaceFs fs = fs();
        fs.ensureLayout("ws1", "sess1");
        fs.writeBytes("ws1", "sess1", "scratch/a.txt", "hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("hello", new String(fs.readBytes("ws1", "sess1", "scratch/a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void pathEscapeRejected() {
        LocalWorkspaceFs fs = fs();
        fs.ensureLayout("ws1", "sess1");
        assertThrows(BizException.class, () -> fs.resolve("ws1", "sess1", "../evil.txt"));
        assertThrows(BizException.class, () -> fs.writeBytes("ws1", "sess1", "scratch/../../x", new byte[]{1}));
    }
}
