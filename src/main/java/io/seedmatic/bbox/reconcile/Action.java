package io.seedmatic.bbox.reconcile;

/**
 * Outcome category for a single reservation row after reconciliation.
 *
 * <p>The {@code WOULD_*} variants are emitted in {@link ReservationReconciler.Mode#DRY_RUN}; their
 * non-prefixed counterparts ({@code CREATED}, {@code UPDATED}) are emitted in {@code APPLY}.
 */
public enum Action {
  /** Live run created a missing row. */
  CREATED,
  /** Live run updated a drifted row. */
  UPDATED,
  /** Row already matches the desired state — no work performed. */
  MATCHING,
  /** Dry run: this row is missing on the bbox and would be created. */
  WOULD_CREATE,
  /** Dry run: this row exists on the bbox but drifts from desired and would be updated. */
  WOULD_UPDATE,
  /**
   * The bbox has a row whose MAC isn't desired and isn't on the ignore list. The reconciler
   * never deletes (today's contract); these surface as {@code EXTRA} for the caller to inspect.
   */
  EXTRA,
  /**
   * The bbox has a row whose MAC matches the consumer's ignore list. Logged for visibility but
   * never touched.
   */
  IGNORED,
  /** A live API call failed; details captured in {@link RowOutcome#failureMessage()}. */
  FAILED
}
