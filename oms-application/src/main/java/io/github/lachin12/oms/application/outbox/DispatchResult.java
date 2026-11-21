package io.github.lachin12.oms.application.outbox;

/**
 * Outcomes for one bounded run. Published counts successful publication with a committed acknowledgement;
 * a publication whose acknowledgement loses its lease counts as leaseLost instead.
 */
public record DispatchResult(int claimed, int published, int rescheduled, int leaseLost) {
    public DispatchResult {
        if (claimed < 0 || published < 0 || rescheduled < 0 || leaseLost < 0
                || (long) published + rescheduled + leaseLost != claimed) {
            throw new IllegalArgumentException("Dispatch counts must be nonnegative and outcomes must equal claims");
        }
    }
}
