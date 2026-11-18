package io.github.sbalci.qupath.workshop;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.*;

/** Pure current-object measurements. This service cannot start a model process. */
public final class WorkshopMeasurementService {
    private WorkshopMeasurementService() {}
    public static JsonObject measure(WorkshopObjectSnapshot snapshot, Collection<String> knownClasses, boolean density) {
        return measure(snapshot,knownClasses,density,Map.of(),true);
    }
    public static JsonObject measure(WorkshopObjectSnapshot snapshot,Collection<String> knownClasses,boolean density,Map<String,String> classMap,boolean nuclear){
        SortedMap<String, Integer> counts = new TreeMap<>(); knownClasses.forEach(c -> counts.put(c, 0));
        JsonArray observations = new JsonArray(); int excluded = 0;
        for (var object : snapshot.objects()) {
            var roi = object.roi();
            if (roi.getZ() != snapshot.scope().getZ() || roi.getT() != snapshot.scope().getT()
                    || roi.getC() != snapshot.scope().getC()
                    || !snapshot.scope().contains(roi.getCentroidX(), roi.getCentroidY())) { excluded++; continue; }
            double area = roi.getArea();
            if (!Double.isFinite(area) || area <= 0) throw new IllegalArgumentException("Invalid result geometry");
            String currentClass=classMap.getOrDefault(object.pathClass(),object.pathClass());
            counts.merge(currentClass, 1, Integer::sum);
            JsonObject row = new JsonObject(); row.addProperty("object_id", object.id()); row.addProperty("class", currentClass);row.addProperty("display_class",object.pathClass());
            row.addProperty("area_px2", area);
            if (snapshot.calibrated()) row.addProperty("area_um2", area * snapshot.areaFactor());
            else row.add("area_um2", JsonNull.INSTANCE);
            observations.add(row);
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        double areaMm2 = snapshot.calibrated() ? snapshot.scope().getArea() * snapshot.areaFactor() / 1e6 : Double.NaN;
        JsonObject j = new JsonObject(); j.addProperty("measurement_version", 1); j.addProperty("snapshot_id", snapshot.id());
        j.addProperty("total_count", total); j.addProperty("excluded_objects", excluded);
        j.addProperty("scope_area_mm2", Double.isFinite(areaMm2) ? areaMm2 : null);
        j.addProperty("density_applicable", density);
        JsonArray classes = new JsonArray();
        for (var entry : counts.entrySet()) {
            JsonObject row = new JsonObject(); row.addProperty("class", entry.getKey()); row.addProperty("count", entry.getValue());
            row.addProperty("percentage", total > 0 ? 100.0 * entry.getValue() / total : null);
            row.addProperty("density_per_mm2", density && areaMm2 > 0 ? entry.getValue() / areaMm2 : null); classes.add(row);
        }
        j.add("classes", classes); j.add("observations", observations);
        j.addProperty(nuclear?"mean_nuclear_area_um2":"mean_object_area_um2", total > 0 && snapshot.calibrated()
                ? observations.asList().stream().mapToDouble(o -> o.getAsJsonObject().get("area_um2").getAsDouble()).sum() / total : null);
        JsonArray unmapped=new JsonArray();counts.keySet().stream().filter(k->!knownClasses.contains(k)).forEach(unmapped::add);j.add("unmapped_classes",unmapped);return j;
    }
    public static void requireNonoverlapping(List<WorkshopObjectSnapshot> snapshots) {
        for (int i=0;i<snapshots.size();i++) for (int k=i+1;k<snapshots.size();k++) {
            var a=snapshots.get(i).scope(); var b=snapshots.get(k).scope();
            if (a.getImagePlane().equals(b.getImagePlane()) && a.getGeometry().intersection(b.getGeometry()).getArea() > 0)
                throw new IllegalArgumentException("Overlapping result scopes must not be aggregated");
        }
    }
}
