package io.seedmatic.bbox.reconcile;

import io.seedmatic.bbox.api.BboxApiClient;
import io.seedmatic.bbox.api.BboxApiClient.DhcpReservation;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compute and (optionally) apply the diff between a set of desired DHCP reservations and the
 * bbox's current {@code /api/v1/dhcp/clients} table.
 *
 * <p>Operates in two modes via {@link Mode}:
 *
 * <ul>
 *   <li>{@link Mode#DRY_RUN} — fetches the bbox table once, classifies every row, returns a
 *       {@link Report} of {@code MATCHING} / {@code WOULD_CREATE} / {@code WOULD_UPDATE} /
 *       {@code EXTRA} / {@code IGNORED} outcomes. No writes hit the bbox. Equivalent to the
 *       {@code diff} sub-command of the original {@code bbox-reconcile} bash script.
 *   <li>{@link Mode#APPLY} — same diff math, but issues POST/PUT requests for missing / drifted
 *       rows. {@code EXTRA} rows surface in the report but are never deleted (today's contract:
 *       desired set says what must exist; orphan removal is a deliberate manual step).
 * </ul>
 *
 * <p>The reconciler is single-use against one bbox snapshot: the constructor fetches the bbox
 * table once via the supplied {@link BboxApiClient}, so subsequent {@link #apply} /
 * {@link #reconcile} calls decide against that single snapshot. Pair one reconciler instance
 * with one logical reconciliation pass; create a new one for the next pass.
 *
 * <p>Identity is the MAC address (case-insensitive). The diff logic doesn't try to match by
 * hostname or by IP — bbox row + desired row are "the same thing" iff their MACs match.
 *
 * <p>The supplied client is borrowed, not owned: callers retain responsibility for closing it.
 */
public final class ReservationReconciler {

  /** Whether the reconciler should issue writes or just compute the diff. */
  public enum Mode {
    DRY_RUN,
    APPLY
  }

  private final BboxApiClient client;
  private final Set<String> ignoreMacs;
  private final Map<String, DhcpReservation> bboxByMac;

  /**
   * @param client open client; the reconciler will issue {@code GET /api/v1/dhcp/clients}
   *     immediately to snapshot the current state, then writes (in APPLY mode) on subsequent
   *     calls.
   * @param ignoreMacs MAC addresses (case-insensitive) the reconciler should leave alone — they
   *     surface as {@link Action#IGNORED} in the report and are never candidates for
   *     {@link Action#EXTRA} or for any write.
   */
  public ReservationReconciler(BboxApiClient client, Collection<String> ignoreMacs) {
    this.client = Objects.requireNonNull(client, "client");
    this.ignoreMacs = normaliseMacs(ignoreMacs);
    this.bboxByMac = fetchSnapshot(client);
  }

  /** Convenience: same as the two-arg constructor with an empty ignore list. */
  public ReservationReconciler(BboxApiClient client) {
    this(client, List.of());
  }

  /** Direct read access to the snapshot — useful for callers that need it for other reporting. */
  public Map<String, DhcpReservation> snapshot() {
    return Map.copyOf(bboxByMac);
  }

  /**
   * Reconcile a single desired row against the snapshot. Idempotent within one reconciler
   * lifetime — the snapshot doesn't refresh between calls, so calling {@code apply} twice with
   * the same {@code want} returns the same outcome.
   *
   * <p>In {@code APPLY} mode, on success the snapshot is mutated to reflect the new state, so
   * a follow-up {@link #reconcile} sees the row as {@code MATCHING}.
   */
  public RowOutcome apply(DesiredReservation want, Mode mode) {
    Objects.requireNonNull(want, "want");
    Objects.requireNonNull(mode, "mode");

    final DhcpReservation have = bboxByMac.get(want.mac());

    if (have == null) {
      return applyCreate(want, mode);
    }
    if (Objects.equals(have.ipaddress(), want.ip())
        && Objects.equals(have.hostname(), want.hostname())) {
      return outcome(Action.MATCHING, want, have, Optional.empty());
    }
    return applyUpdate(want, have, mode);
  }

  /**
   * Reconcile a whole desired set in one pass. Returns a {@link Report} including:
   *
   * <ul>
   *   <li>one outcome per desired row (CREATED / UPDATED / MATCHING / WOULD_CREATE /
   *       WOULD_UPDATE / FAILED depending on mode and bbox response),
   *   <li>one {@code EXTRA} outcome for every bbox row whose MAC isn't in the desired set and
   *       isn't on the ignore list,
   *   <li>one {@code IGNORED} outcome for every bbox row whose MAC matches the ignore list.
   * </ul>
   */
  public Report reconcile(List<DesiredReservation> desired, Mode mode) {
    Objects.requireNonNull(desired, "desired");
    Objects.requireNonNull(mode, "mode");

    final List<RowOutcome> all = new java.util.ArrayList<>(desired.size() + bboxByMac.size());
    final Set<String> desiredMacs = desired.stream()
        .map(DesiredReservation::mac)
        .collect(Collectors.toSet());

    for (DesiredReservation want : desired) {
      all.add(apply(want, mode));
    }

    for (Map.Entry<String, DhcpReservation> entry : bboxByMac.entrySet()) {
      final String mac = entry.getKey();
      if (desiredMacs.contains(mac)) {
        continue;
      }
      final DhcpReservation row = entry.getValue();
      final Action action = ignoreMacs.contains(mac) ? Action.IGNORED : Action.EXTRA;
      all.add(new RowOutcome(
          action,
          row.macaddress(),
          row.ipaddress(),
          row.hostname(),
          Optional.empty(),
          OptionalInt.of(row.id()),
          Optional.empty(),
          Optional.empty(),
          Optional.empty()));
    }

    return new Report(mode == Mode.DRY_RUN, List.copyOf(all));
  }

  private RowOutcome applyCreate(DesiredReservation want, Mode mode) {
    if (mode == Mode.DRY_RUN) {
      return new RowOutcome(
          Action.WOULD_CREATE,
          want.mac(),
          want.ip(),
          want.hostname(),
          Optional.of(want),
          OptionalInt.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }
    try {
      final int status = client.createReservation(want.mac(), want.ip(), want.hostname());
      if (status == 200 || status == 201) {
        // Reflect the newly-created row in the snapshot so subsequent calls see MATCHING.
        // We don't have the bbox-assigned id without a refetch; -1 is a placeholder.
        bboxByMac.put(want.mac(),
            new DhcpReservation(-1, want.hostname(), want.mac(), want.ip()));
        return new RowOutcome(
            Action.CREATED,
            want.mac(),
            want.ip(),
            want.hostname(),
            Optional.of(want),
            OptionalInt.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
      }
      return failure(want, OptionalInt.empty(),
          Optional.empty(), Optional.empty(),
          "CREATE returned HTTP " + status);
    } catch (Exception ex) {
      return failure(want, OptionalInt.empty(),
          Optional.empty(), Optional.empty(),
          "CREATE failed: " + ex.getMessage());
    }
  }

  private RowOutcome applyUpdate(DesiredReservation want, DhcpReservation have, Mode mode) {
    if (mode == Mode.DRY_RUN) {
      return new RowOutcome(
          Action.WOULD_UPDATE,
          want.mac(),
          want.ip(),
          want.hostname(),
          Optional.of(want),
          OptionalInt.of(have.id()),
          Optional.of(have.ipaddress()),
          Optional.of(have.hostname()),
          Optional.empty());
    }
    try {
      final int status =
          client.updateReservation(have.id(), want.mac(), want.ip(), want.hostname());
      if (status == 200) {
        bboxByMac.put(want.mac(),
            new DhcpReservation(have.id(), want.hostname(), want.mac(), want.ip()));
        return new RowOutcome(
            Action.UPDATED,
            want.mac(),
            want.ip(),
            want.hostname(),
            Optional.of(want),
            OptionalInt.of(have.id()),
            Optional.of(have.ipaddress()),
            Optional.of(have.hostname()),
            Optional.empty());
      }
      return failure(want, OptionalInt.of(have.id()),
          Optional.of(have.ipaddress()), Optional.of(have.hostname()),
          "UPDATE returned HTTP " + status);
    } catch (Exception ex) {
      return failure(want, OptionalInt.of(have.id()),
          Optional.of(have.ipaddress()), Optional.of(have.hostname()),
          "UPDATE failed: " + ex.getMessage());
    }
  }

  private RowOutcome outcome(
      Action action, DesiredReservation want, DhcpReservation have, Optional<String> failure) {
    return new RowOutcome(
        action,
        want.mac(),
        want.ip(),
        want.hostname(),
        Optional.of(want),
        OptionalInt.of(have.id()),
        Optional.of(have.ipaddress()),
        Optional.of(have.hostname()),
        failure);
  }

  private RowOutcome failure(
      DesiredReservation want,
      OptionalInt id,
      Optional<String> previousIp,
      Optional<String> previousHostname,
      String message) {
    return new RowOutcome(
        Action.FAILED,
        want.mac(),
        want.ip(),
        want.hostname(),
        Optional.of(want),
        id,
        previousIp,
        previousHostname,
        Optional.of(message));
  }

  private static Set<String> normaliseMacs(Collection<String> macs) {
    if (macs == null || macs.isEmpty()) {
      return Set.of();
    }
    final Set<String> out = new HashSet<>(macs.size());
    for (String m : macs) {
      if (m != null && !m.isBlank()) {
        out.add(m.toLowerCase(Locale.ROOT));
      }
    }
    return Set.copyOf(out);
  }

  private static Map<String, DhcpReservation> fetchSnapshot(BboxApiClient client) {
    final List<DhcpReservation> rows;
    try {
      rows = client.listReservations();
    } catch (Exception ex) {
      throw new IllegalStateException(
          "Failed to fetch bbox snapshot: " + ex.getMessage(), ex);
    }
    final Map<String, DhcpReservation> out = new LinkedHashMap<>(rows.size() * 2);
    for (DhcpReservation row : rows) {
      if (row.macaddress() == null || row.macaddress().isBlank()) {
        continue;
      }
      out.put(row.macaddress().toLowerCase(Locale.ROOT), row);
    }
    return out;
  }
}
