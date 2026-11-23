package glucoselab;

/** Window metrics for one subject. Percentages are 0 to 100. */
public record Summary(
        String subjectId, int days, int readings, int expectedReadings, double coveragePct, boolean sufficient,
        int gaps, double meanMgdl, double sdMgdl, double cvPct, double gmiPct,
        double timeInRangePct, double timeBelowPct, double timeVeryLowPct, double timeAbovePct, double timeVeryHighPct) {}
