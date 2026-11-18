package io.github.sbalci.qupath.workshop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;
import org.locationtech.jts.io.WKBWriter;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.io.GsonTools;

/** Detached geometry/class snapshot; no cached MeasurementList values are used. */
public final class WorkshopObjectSnapshot {
    public record Observation(String id, String type, String pathClass, ROI roi) {}
    private final List<Observation> objects;
    private final ROI scope;
    private final double pixelWidth, pixelHeight;
    private final JsonObject json;
    public WorkshopObjectSnapshot(ROI scope, Collection<? extends PathObject> objects, double pw, double ph) {
        if (scope == null || !scope.isArea() || scope.getArea() <= 0) throw new IllegalArgumentException("A nonempty area scope is required");
        this.scope = scope; pixelWidth = pw; pixelHeight = ph;
        List<Observation> rows = new ArrayList<>(); Set<String> ids = new HashSet<>();Map<String,String> originalClasses=new HashMap<>();
        for (PathObject object : objects) {
            String id = object.getID().toString();
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate object ID");
            if (object.getROI() == null || !object.getROI().isArea()) throw new IllegalArgumentException("Result contains a non-area object");
            if(object.hasMetadata()&&object.getMetadata().containsKey(WorkshopRunRecord.ORIGINAL_CLASS_TAG))originalClasses.put(id,object.getMetadata().get(WorkshopRunRecord.ORIGINAL_CLASS_TAG));
            rows.add(new Observation(id, object.getClass().getSimpleName(),
                    object.getPathClass() == null ? "(unclassified)" : object.getPathClass().toString(), object.getROI()));
        }
        rows.sort(Comparator.comparing(Observation::id)); this.objects = List.copyOf(rows);
        json = new JsonObject(); json.addProperty("snapshot_version", 1);
        json.addProperty("pixel_width_um", finite(pw)); json.addProperty("pixel_height_um", finite(ph));
        json.add("scope", roiJson(scope));
        JsonArray entries = new JsonArray();
        for (Observation row : rows) {
            JsonObject entry = new JsonObject(); entry.addProperty("object_id", row.id()); entry.addProperty("type", row.type());
            entry.addProperty("class", row.pathClass()); entry.add("roi", roiJson(row.roi())); entries.add(entry);
            if(originalClasses.containsKey(row.id()))entry.addProperty("original_class",originalClasses.get(row.id()));
        }
        json.add("objects", entries);
    }
    private static Double finite(double n) { return Double.isFinite(n) && n > 0 ? n : null; }
    static JsonObject roiJson(ROI roi) {
        JsonObject j = new JsonObject();
        var geometry = roi.getGeometry().copy(); geometry.normalize();
        j.addProperty("wkb", HexFormat.of().formatHex(new WKBWriter(2).write(geometry)));
        j.addProperty("c", roi.getC()); j.addProperty("z", roi.getZ()); j.addProperty("t", roi.getT()); return j;
    }
    public String id() { return WorkshopRunStore.sha256(json.toString()); }
    public JsonObject json() { return json.deepCopy(); }
    public List<Observation> objects() { return objects; }
    public ROI scope() { return scope; }
    public boolean calibrated() { return Double.isFinite(pixelWidth) && pixelWidth > 0 && Double.isFinite(pixelHeight) && pixelHeight > 0; }
    public double areaFactor() { return pixelWidth * pixelHeight; }
    public static String serializeRoi(ROI roi) { return GsonTools.getInstance().toJson(roi, ROI.class); }
    public static ROI deserializeRoi(String json) { return GsonTools.getInstance().fromJson(json, ROI.class); }
}
