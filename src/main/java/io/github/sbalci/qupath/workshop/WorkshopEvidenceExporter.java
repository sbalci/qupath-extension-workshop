package io.github.sbalci.qupath.workshop;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Immutable TSV/JSON bundle, committed by one same-volume directory rename. */
public final class WorkshopEvidenceExporter {
    private WorkshopEvidenceExporter() {}
    static String cell(String value) {
        if(value==null)return "";
        String safe=value.replace('\t',' ').replace('\r',' ').replace('\n',' ');
        return !safe.isEmpty()&&"=+@-".indexOf(safe.charAt(0))>=0?"'"+safe:safe;
    }
    static String value(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }
    public static Path export(Path destinationParent, List<WorkshopRunRecord> records) throws IOException {
        if (records.isEmpty()) throw new IllegalArgumentException("Select at least one recorded result");
        Path parent = destinationParent.toRealPath();
        Path staging = Files.createTempDirectory(parent, ".atolye-export-");
        List<Path> created = new ArrayList<>();
        try {
            StringBuilder summary = new StringBuilder("image_id\tadapter_id\tmodel_identity\tclass\tcount\tpercentage\tdensity_per_mm2\tscope_area_mm2\tregion_count\n");
            StringBuilder perRegion = new StringBuilder("image_id\tanalysis_unit_id\tresult_set_id\tclass\tcount\tpercentage\tdensity_per_mm2\tscope_area_mm2\tsnapshot_id\n");
            StringBuilder coverage = new StringBuilder("image_id\tanalysis_unit_id\tresult_set_id\texecution_status\tpersistence_status\treview_status\tmeasurement_status\treason\n");
            StringBuilder raw = new StringBuilder("image_id\tresult_set_id\tobject_id\tclass\tarea_px2\tarea_um2\tsnapshot_id\n");
            JsonObject evidence = new JsonObject(); evidence.addProperty("schema_version", 1);
            evidence.addProperty("created_at", java.time.Instant.now().toString()); JsonArray rows = new JsonArray();
            Set<String> seen = new HashSet<>(); Map<String,List<qupath.lib.roi.interfaces.ROI>> scopes = new HashMap<>();
            boolean complete = true;Map<String,Aggregate> aggregates=new TreeMap<>();
            for (WorkshopRunRecord record : records) {
                record.checkWritable(); JsonObject j = record.json();
                if (!seen.add(record.resultId())) throw new IllegalArgumentException("Duplicate result set selected");
                boolean success = record.get("execution_status").equals("succeeded") && record.get("persistence_status").equals("saved");
                boolean measured = success && j.has("measurements") && record.get("saved_snapshot_id").equals(record.get("measurement_snapshot_id"));
                if (success && !measured) throw new IllegalStateException("Measurements are stale or missing; refresh before export");
                if (measured) {
                    var roi = WorkshopObjectSnapshot.deserializeRoi(record.get("measurement_scope_json").isBlank()?record.get("scope_json"):record.get("measurement_scope_json"));
                    var prior = scopes.computeIfAbsent(record.get("image_entry_id"), k -> new ArrayList<>());
                    for (var p : prior) if (p.getImagePlane().equals(roi.getImagePlane()) && p.getGeometry().intersection(roi.getGeometry()).getArea()>0)
                        throw new IllegalArgumentException("Overlapping scopes or duplicate result copies selected");
                    prior.add(roi);
                    JsonObject m=j.getAsJsonObject("measurements");
                    String identity=j.has("model_identity")?value(j.getAsJsonObject("model_identity"),"weight_sha256"):"";
                    if(identity.isBlank())identity=record.resultId();
                    String group=record.get("image_entry_id")+"/"+record.get("adapter_id")+"/"+identity;
                    final String modelIdentity=identity;
                    var aggregate=aggregates.computeIfAbsent(group,k->new Aggregate(record.get("image_entry_id"),record.get("adapter_id"),modelIdentity));
                    aggregate.add(m);
                    line(perRegion,record.get("image_entry_id"),record.get("analysis_unit_id"),record.resultId(),"__total__",value(m,"total_count"),
                            m.get("total_count").getAsInt()>0?"100":"","",value(m,"scope_area_mm2"),record.get("measurement_snapshot_id"));
                    for (JsonElement el:m.getAsJsonArray("classes")) {
                        JsonObject c=el.getAsJsonObject();
                        line(perRegion, record.get("image_entry_id"),record.get("analysis_unit_id"),record.resultId(),value(c,"class"),value(c,"count"),value(c,"percentage"),
                                value(c,"density_per_mm2"),value(m,"scope_area_mm2"),record.get("measurement_snapshot_id"));
                    }
                    for (JsonElement el:m.getAsJsonArray("observations")) {
                        JsonObject o=el.getAsJsonObject();
                        line(raw,record.get("image_entry_id"),record.resultId(),value(o,"object_id"),value(o,"class"),value(o,"area_px2"),value(o,"area_um2"),record.get("measurement_snapshot_id"));
                    }
                } else complete=false;
                if(j.has("prompt_outcomes"))for(var item:j.getAsJsonObject("prompt_outcomes").getAsJsonArray("items")){
                    var outcome=item.getAsJsonObject();boolean ok=outcome.get("ok").getAsBoolean();if(!ok)complete=false;
                    line(coverage,record.get("image_entry_id"),record.get("analysis_unit_id")+"/"+value(outcome,"id"),record.resultId(),
                            ok?"succeeded":"failed",record.get("persistence_status"),record.get("review_status"),measured?"current":"unavailable",ok?"":"point prediction unavailable");
                }
                line(coverage,record.get("image_entry_id"),record.get("analysis_unit_id"),record.resultId(),record.get("execution_status"),
                        record.get("persistence_status"),record.get("review_status"),measured?"current":"unavailable",record.get("failure_reason"));
                // An allow-list prevents source paths, environment variables and tokens leaking into portable evidence.
                JsonObject safe=new JsonObject();
                for (String key: List.of("schema_version","run_id","result_set_id","project_id","image_entry_id","analysis_unit_id","adapter_id",
                        "adapter_version","created_at","execution_status","persistence_status","review_status","reviewed_snapshot_id",
                        "measurement_snapshot_id","saved_snapshot_id","scope_json","measurement_scope_json","input_signature","source_identity_strength","source_sha256","snapshot","prediction_snapshot","initial_recorded_snapshot","measurements","prompt_outcomes","referenced_object_ids","previous_classes"))
                    if(j.has(key)) safe.add(key,j.get(key).deepCopy());
                for(String key:List.of("model_identity","runtime_identity","effective_settings")) if(j.has(key)) safe.add(key,portable(j.get(key)));
                rows.add(safe);
            }
            for(var aggregate:aggregates.values())aggregate.write(summary);
            evidence.addProperty("complete",complete); evidence.addProperty("aggregation","sum counts/eligible nonoverlapping areas by image, adapter and verified weight identity; unknown identities stay separate"); evidence.add("records",rows);
            write(staging,"summary.tsv",summary.toString(),created); write(staging,"per-region.tsv",perRegion.toString(),created);
            write(staging,"coverage.tsv",coverage.toString(),created); write(staging,"observations.tsv",raw.toString(),created);
            write(staging,"evidence.json",WorkshopRunStore.JSON.toJson(evidence),created);
            Path result=parent.resolve("atolye-evidence-"+UUID.randomUUID());
            Files.move(staging,result,StandardCopyOption.ATOMIC_MOVE); return result;
        } finally {
            if(Files.exists(staging)) { for(Path file:created) Files.deleteIfExists(file); Files.deleteIfExists(staging); }
        }
    }
    private static final class Aggregate{
        final String image,adapter,identity;final Map<String,Long> counts=new TreeMap<>();long total;int regions;double area;boolean calibrated=true,density=true;
        Aggregate(String image,String adapter,String identity){this.image=image;this.adapter=adapter;this.identity=identity;}
        void add(JsonObject m){
            total+=m.get("total_count").getAsLong();regions++;
            if(m.get("scope_area_mm2").isJsonNull())calibrated=false;else area+=m.get("scope_area_mm2").getAsDouble();
            density&=m.get("density_applicable").getAsBoolean();
            for(var el:m.getAsJsonArray("classes")){var row=el.getAsJsonObject();counts.merge(row.get("class").getAsString(),row.get("count").getAsLong(),Long::sum);}
        }
        void write(StringBuilder out){
            line(out,image,adapter,identity,"__total__",Long.toString(total),total>0?"100":"",calibrated&&density&&area>0?Double.toString(total/area):"",calibrated?Double.toString(area):"",Integer.toString(regions));
            for(var e:counts.entrySet())line(out,image,adapter,identity,e.getKey(),e.getValue().toString(),total>0?Double.toString(100d*e.getValue()/total):"",calibrated&&density&&area>0?Double.toString(e.getValue()/area):"",calibrated?Double.toString(area):"",Integer.toString(regions));
        }
    }
    static JsonElement portable(JsonElement input) {
        if(input.isJsonObject()) {
            JsonObject out=new JsonObject();
            for(var entry:input.getAsJsonObject().entrySet()) {
                String key=entry.getKey().toLowerCase(Locale.ROOT);
                if(key.equals("class_mapping")||key.equals("previous_classes")){out.add(entry.getKey(),entry.getValue().deepCopy());continue;}
                if(key.contains("path")||key.contains("folder")||key.contains("directory")||key.equals("token")||key.endsWith("_token")||key.equals("secret")||key.endsWith("_secret")||key.contains("uri")) continue;
                out.add(entry.getKey(),portable(entry.getValue()));
            }
            return out;
        }
        if(input.isJsonArray()) { JsonArray out=new JsonArray(); input.getAsJsonArray().forEach(e->out.add(portable(e)));return out; }
        if(input.isJsonPrimitive()&&input.getAsJsonPrimitive().isString()) {
            String s=input.getAsString();
            if(s.matches("(?s).*([A-Za-z]:[\\\\/]|file:|https?://|^/|^\\\\\\\\).*")) return new JsonPrimitive("[local reference omitted]");
        }
        return input.deepCopy();
    }
    private static void line(StringBuilder b,String...values) { for(int i=0;i<values.length;i++){if(i>0)b.append('\t');b.append(cell(values[i]));}b.append('\n'); }
    private static void write(Path dir,String name,String text,List<Path> created)throws IOException {
        Path file=dir.resolve(name);created.add(file);Files.writeString(file,text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
}
