package glucoselab;

import java.util.Map;

/** A finding that cites the exact summary fields it was computed from. evidence keys are Summary field names. */
public record Insight(String code, String text, Map<String, Double> evidence) {}
