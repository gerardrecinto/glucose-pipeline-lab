package glucoselab;

/** One CGM sample. ts is epoch seconds; mgdl is glucose in mg/dL. subjectId is a synthetic pseudonym, never a real identifier. */
public record Reading(String deviceId, String subjectId, long ts, int mgdl) {}
