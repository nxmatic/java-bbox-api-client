package io.nxmatic.bbox.reconcile;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Outcome of reconciling one row against the bbox.
 *
 * <p>For {@code desired}-driven actions ({@link Action#CREATED}, {@link Action#UPDATED},
 * {@link Action#MATCHING}, {@link Action#WOULD_CREATE}, {@link Action#WOULD_UPDATE},
 * {@link Action#FAILED}), the {@code desired} field is populated. For bbox-side findings
 * ({@link Action#EXTRA}, {@link Action#IGNORED}), {@code desired} is empty and {@code mac} /
 * {@code ip} / {@code hostname} reflect what the bbox shows.
 *
 * <p>{@code bboxId} is set when a corresponding row already exists on the bbox at snapshot
 * time. {@code previousIp} / {@code previousHostname} carry the pre-update values for
 * UPDATED / WOULD_UPDATE, useful for diff reporting.
 */
public record RowOutcome(
    Action action,
    String mac,
    String ip,
    String hostname,
    Optional<DesiredReservation> desired,
    OptionalInt bboxId,
    Optional<String> previousIp,
    Optional<String> previousHostname,
    Optional<String> failureMessage) {}
