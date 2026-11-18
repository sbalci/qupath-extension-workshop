package io.github.sbalci.qupath.workshop;

/** Exact identity rules shared by GUI and queued/headless execution. */
public final class WorkshopExecutionPolicy {
    public enum Action { ADD, COMPLETE_MISSING, REGENERATE_SELECTED, MEASURE_ONLY }
    private WorkshopExecutionPolicy(){}
    public static boolean reusable(WorkshopRunRecord record,String signature){
        return record.writable()&&record.get("source_identity_strength").equals("strong")
                &&record.get("execution_status").equals("succeeded")&&record.get("persistence_status").equals("saved")
                &&!signature.isBlank()&&record.get("input_signature").equals(signature);
    }
    public static void requireReplacement(WorkshopRunRecord record,boolean explicitConfirmation){
        record.checkWritable();
        if(!explicitConfirmation)throw new IllegalStateException("Replacement requires an explicit confirmation");
        if(!record.get("persistence_status").equals("saved"))throw new IllegalStateException("Reconcile this result before replacement");
    }
}
