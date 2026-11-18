package io.github.sbalci.qupath.workshop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Project-local records and an OS file lock shared by all QuPath sessions. */
public final class WorkshopRunStore {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path root;
    public WorkshopRunStore(Path projectDirectory) throws IOException {
        Path project = projectDirectory.toRealPath();
        root = project.resolve("workshop-analysis");
        if (Files.isSymbolicLink(root)) throw new IOException("Workflow directory must not be a symbolic link");
        Files.createDirectories(root);
        if (!root.toRealPath().startsWith(project)) throw new IOException("Workflow directory escapes project");
    }
    public Path root() { return root; }
    public Lock lock() throws IOException {
        Path file = root.resolve("workflow.lock");
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid lock path");
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Another workflow operation is writing this project");
            return new Lock(channel, lock);
        } catch (IOException | OverlappingFileLockException e) {
            channel.close(); throw new IOException("Another workflow operation is writing this project", e);
        }
    }
    public String projectId() throws IOException {
        try (Lock ignored = lock()) {
            Path path = root.resolve("project.json");
            if (Files.exists(path)) return readJson(path).get("project_id").getAsString();
            JsonObject id = new JsonObject(); id.addProperty("project_id", UUID.randomUUID().toString());
            writeJson(path, id); return id.get("project_id").getAsString();
        }
    }
    private Path recordPath(String id) { return root.resolve("run-" + UUID.fromString(id) + ".json"); }
    public WorkshopRunRecord read(String id) throws IOException { return new WorkshopRunRecord(readJson(recordPath(id))); }
    /** Caller holds lock for a multi-step transaction. */
    public void write(WorkshopRunRecord record) throws IOException {
        record.checkWritable();
        Path path = recordPath(record.id());
        if (Files.exists(path)) {
            WorkshopRunRecord previous = read(record.id()); previous.checkWritable();
            if (!previous.resultId().equals(record.resultId()) || !previous.get("image_entry_id").equals(record.get("image_entry_id")))
                throw new IOException("Run identity conflict");
        }
        writeJson(path, record.json());
    }
    public List<WorkshopRunRecord> list() throws IOException {
        List<WorkshopRunRecord> out = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "run-*.json")) {
            for (Path file : files) out.add(new WorkshopRunRecord(readJson(file)));
        }
        out.sort(Comparator.comparing(r -> r.get("created_at"))); return out;
    }
    static JsonObject readJson(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path) > 32L * 1024 * 1024)
            throw new IOException("Invalid workflow record: " + path.getFileName());
        try { return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("Invalid JSON: " + path.getFileName(), e); }
    }
    static void writeJson(Path path, JsonObject value) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Refusing symbolic link destination");
        Path temporary = Files.createTempFile(path.getParent(), ".workflow-", ".tmp");
        try {
            byte[] bytes = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
            if(bytes.length>32L*1024*1024)throw new IOException("Workflow record exceeds 32 MiB; use a smaller ROI");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    public static String sha256(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("Missing file: " + file.getFileName());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(file)) {
                byte[] buffer = new byte[1024 * 1024]; int n;
                while ((n = stream.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Verification cancelled");
                    digest.update(buffer, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static final class Lock implements AutoCloseable {
        private final FileChannel channel; private final FileLock lock;
        Lock(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
    }
}
