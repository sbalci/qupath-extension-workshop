package io.github.sbalci.qupath.workshop;

import com.google.gson.JsonObject;
import java.util.UUID;

/** Versioned durable record. Keep inference, persistence, review and measurement states separate. */
public final class WorkshopRunRecord {
    public static final int SCHEMA_VERSION = 1;
    public static final String RESULT_TAG = "atolye.workflow.result";
    public static final String ROLE_TAG = "atolye.workflow.role";
    public static final String ORIGINAL_CLASS_TAG = "atolye.workflow.original-class";
    private final JsonObject json;

    public WorkshopRunRecord(JsonObject json) { this.json = json.deepCopy(); }
    public static WorkshopRunRecord create(String projectId, String imageId, String adapter) {
        JsonObject j = new JsonObject();
        j.addProperty("schema_version", SCHEMA_VERSION);
        j.addProperty("run_id", UUID.randomUUID().toString());
        j.addProperty("result_set_id", UUID.randomUUID().toString());
        j.addProperty("project_id", projectId);
        j.addProperty("image_entry_id", imageId);
        j.addProperty("adapter_id", adapter);
        j.addProperty("adapter_version", 1);
        j.addProperty("execution_status", "requested");
        j.addProperty("persistence_status", "unsaved");
        j.addProperty("review_status", "unreviewed");
        j.addProperty("created_at", java.time.Instant.now().toString());
        return new WorkshopRunRecord(j);
    }
    public JsonObject json() { return json.deepCopy(); }
    public String get(String key) { return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : ""; }
    public boolean writable() { return json.has("schema_version") && json.get("schema_version").getAsInt() == SCHEMA_VERSION; }
    public WorkshopRunRecord with(String key, String value) {
        checkWritable(); JsonObject copy = json(); copy.addProperty(key, value); return new WorkshopRunRecord(copy);
    }
    public WorkshopRunRecord withJson(String key, com.google.gson.JsonElement value) {
        checkWritable(); JsonObject copy = json(); copy.add(key, value); return new WorkshopRunRecord(copy);
    }
    public String id() { return get("run_id"); }
    public String resultId() { return get("result_set_id"); }
    public void checkWritable() { if (!writable()) throw new IllegalStateException("Unsupported record schema; read only"); }
}
