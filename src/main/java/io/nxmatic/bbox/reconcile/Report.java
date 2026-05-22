package io.nxmatic.bbox.reconcile;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregate of per-row outcomes from one reconciliation pass.
 *
 * <p>Intended for printing diff reports, building Pulumi resources, or asserting in tests.
 * The categorical filters ({@link #created()}, {@link #drift()}, etc.) are convenience views
 * over {@link #outcomes()} — the canonical data is the unfiltered list.
 */
public record Report(boolean dryRun, List<RowOutcome> outcomes) {

  public Report {
    outcomes = List.copyOf(outcomes);
  }

  /** Outcomes for desired rows missing on the bbox (CREATED or WOULD_CREATE). */
  public List<RowOutcome> created() {
    return filter(Action.CREATED, Action.WOULD_CREATE);
  }

  /** Outcomes for desired rows whose bbox state drifts (UPDATED or WOULD_UPDATE). */
  public List<RowOutcome> drift() {
    return filter(Action.UPDATED, Action.WOULD_UPDATE);
  }

  /** Outcomes for desired rows that already match the bbox. */
  public List<RowOutcome> matching() {
    return filter(Action.MATCHING);
  }

  /** Outcomes for bbox rows that aren't desired and aren't ignored — surfaced for review. */
  public List<RowOutcome> extras() {
    return filter(Action.EXTRA);
  }

  /** Outcomes for bbox rows the consumer told us to leave alone. */
  public List<RowOutcome> ignored() {
    return filter(Action.IGNORED);
  }

  /** Outcomes for live calls that failed. Empty in dry-run mode. */
  public List<RowOutcome> failed() {
    return filter(Action.FAILED);
  }

  /** Aggregate count for each {@link Action}, including zero entries. */
  public Map<Action, Integer> counts() {
    final EnumMap<Action, Integer> counts = new EnumMap<>(Action.class);
    for (Action action : Action.values()) {
      counts.put(action, 0);
    }
    for (RowOutcome outcome : outcomes) {
      counts.merge(outcome.action(), 1, (a, b) -> a + b);
    }
    return counts;
  }

  private List<RowOutcome> filter(Action... actions) {
    return outcomes.stream().filter(o -> contains(actions, o.action())).toList();
  }

  private static boolean contains(Action[] needles, Action haystack) {
    for (Action needle : needles) {
      if (needle == haystack) {
        return true;
      }
    }
    return false;
  }
}
