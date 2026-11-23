package glucoselab;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Filters any provider's output, including a future LLM. An insight is kept only if its evidence cites real
 * Summary fields with the values the summary holds, and its text has no dosing or treatment language.
 */
public final class InsightGuard {
    private static final Pattern ADVICE = Pattern.compile(
            "\\b(insulin|dose|dosing|units?|inject|take|eat|medication|prescri\\w*|diagnos\\w*)\\b", Pattern.CASE_INSENSITIVE);
    private static final Map<String, java.util.function.Function<Summary, Double>> FIELDS = Map.of(
            "coveragePct", Summary::coveragePct,
            "timeInRangePct", Summary::timeInRangePct,
            "timeBelowPct", Summary::timeBelowPct,
            "timeVeryLowPct", Summary::timeVeryLowPct,
            "timeAbovePct", Summary::timeAbovePct,
            "cvPct", Summary::cvPct,
            "meanMgdl", Summary::meanMgdl);

    private InsightGuard() {}

    public static List<Insight> apply(Summary s, List<Insight> candidates) {
        List<Insight> kept = new ArrayList<>();
        for (Insight i : candidates) {
            if (grounded(s, i) && !ADVICE.matcher(i.text()).find()) kept.add(i);
        }
        return kept;
    }

    private static boolean grounded(Summary s, Insight i) {
        if (i.evidence() == null || i.evidence().isEmpty()) return false;
        Set<String> keys = i.evidence().keySet();
        for (String k : keys) {
            var f = FIELDS.get(k);
            if (f == null) return false;
            if (Math.abs(f.apply(s) - i.evidence().get(k)) > 0.0001) return false;
        }
        return true;
    }
}
