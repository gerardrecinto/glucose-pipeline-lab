package glucoselab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic default. Says what the numbers show against the consensus targets, never what to do about them. */
public final class RuleInsights implements InsightProvider {
    @Override
    public List<Insight> insights(Summary s) {
        List<Insight> out = new ArrayList<>();
        if (!s.sufficient()) {
            out.add(new Insight("insufficient_data",
                    "Sensor coverage is below 70%, so the metrics are not reliable enough to interpret.",
                    ev("coveragePct", s.coveragePct())));
            return out;
        }
        if (s.timeInRangePct() < 70) {
            out.add(new Insight("tir_below_target",
                    "Time in range (70 to 180 mg/dL) is below the 70% target.", ev("timeInRangePct", s.timeInRangePct())));
        }
        if (s.timeBelowPct() >= 4) {
            out.add(new Insight("tbr_above_target",
                    "Time below 70 mg/dL is at or above the 4% limit.", ev("timeBelowPct", s.timeBelowPct())));
        }
        if (s.timeVeryLowPct() >= 1) {
            out.add(new Insight("very_low_above_target",
                    "Time below 54 mg/dL is at or above the 1% limit.", ev("timeVeryLowPct", s.timeVeryLowPct())));
        }
        if (s.timeAbovePct() > 25) {
            out.add(new Insight("tar_above_target",
                    "Time above 180 mg/dL is above the 25% limit.", ev("timeAbovePct", s.timeAbovePct())));
        }
        if (s.cvPct() > 36) {
            out.add(new Insight("high_variability",
                    "Glucose variability (CV) is above the 36% threshold.", ev("cvPct", s.cvPct())));
        }
        return out;
    }

    private static Map<String, Double> ev(String key, double value) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }
}
