package io.seedmatic.bbox.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.bbox.api.BboxApiClient;
import io.seedmatic.bbox.api.BboxApiClient.DhcpReservation;
import io.seedmatic.bbox.reconcile.ReservationReconciler.Mode;
import io.seedmatic.bbox.test.BboxSecrets;
import io.seedmatic.bbox.test.BboxSecrets.Coordinates;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Live-router tests for {@link ReservationReconciler}.
 *
 * <p>Same safety stance as {@code BboxApiClientLiveTest}: a single locally-administered MAC is
 * used for the test row; pre-existing rows at that MAC get cleaned up before the test starts;
 * {@link AfterEach} best-effort deletes the test row.
 */
final class ReservationReconcilerLiveTest {

  private static final String DEFAULT_TEST_MAC = "02:00:bb:ff:ff:01";
  private static final String DEFAULT_TEST_IP = "192.168.1.250";
  private static final String TEST_HOSTNAME = "java-bbox-api-client-test";

  private final String testMac =
      System.getenv().getOrDefault("BBOX_TEST_MAC", DEFAULT_TEST_MAC).toLowerCase();
  private final String testIp =
      System.getenv().getOrDefault("BBOX_TEST_IP", DEFAULT_TEST_IP);

  private BboxApiClient client;

  @BeforeEach
  void openSession() throws Exception {
    final Coordinates secrets = BboxSecrets.read();
    this.client = BboxApiClient.open(secrets.uri(), secrets.password());

    // Best-effort cleanup of any stale test row from a previous run; poll briefly because
    // back-to-back surefire invocations sometimes race against the bbox's commit latency.
    waitUntilTestRowAbsent();
  }

  private void waitUntilTestRowAbsent() throws Exception {
    for (int attempt = 0; attempt < 5; attempt++) {
      final Optional<DhcpReservation> stale = client.findByMac(testMac);
      if (stale.isEmpty()) {
        return;
      }
      try {
        client.deleteReservation(stale.get().id());
      } catch (Exception ignored) {
        // best-effort; will retry on next iteration
      }
      Thread.sleep(200);
    }
    Assumptions.assumeFalse(
        client.findByMac(testMac).isPresent(),
        "stale test row " + testMac + " could not be deleted after retries; manual cleanup required");
  }

  @AfterEach
  void cleanup() throws Exception {
    if (client == null) {
      return;
    }
    try {
      client.findByMac(testMac).ifPresent(row -> {
        try {
          client.deleteReservation(row.id());
        } catch (Exception ignored) {
          // best-effort
        }
      });
    } finally {
      client.close();
    }
  }

  @Test
  void dryRunReportsWouldCreateForMissingRow() throws Exception {
    final ReservationReconciler reconciler = new ReservationReconciler(client);
    final DesiredReservation want = new DesiredReservation(testMac, testIp, TEST_HOSTNAME);

    final Report report = reconciler.reconcile(List.of(want), Mode.DRY_RUN);

    assertTrue(report.dryRun(), "report should be marked dry-run");
    assertEquals(1, report.created().size(), "exactly one WOULD_CREATE expected");
    final RowOutcome outcome = report.created().get(0);
    assertEquals(Action.WOULD_CREATE, outcome.action());
    assertEquals(testMac, outcome.mac());
    assertEquals(testIp, outcome.ip());
    assertFalse(outcome.bboxId().isPresent(), "no bbox id when row is missing");
    assertFalse(client.findByMac(testMac).isPresent(),
        "dry-run must not have created the row");
  }

  @Test
  void applyCreatesThenDryRunReportsMatching() throws Exception {
    final ReservationReconciler reconciler = new ReservationReconciler(client);
    final DesiredReservation want = new DesiredReservation(testMac, testIp, TEST_HOSTNAME);

    final Report applyReport = reconciler.reconcile(List.of(want), Mode.APPLY);
    assertEquals(1, applyReport.created().size(), "one CREATED expected");
    assertEquals(Action.CREATED, applyReport.created().get(0).action());
    assertTrue(client.findByMac(testMac).isPresent(),
        "row must exist on bbox after APPLY");

    // Same reconciler instance, second pass: snapshot was mutated to reflect the create,
    // so a follow-up reconcile must report MATCHING for this row.
    final Report secondReport = reconciler.reconcile(List.of(want), Mode.DRY_RUN);
    assertEquals(1, secondReport.matching().size(),
        "follow-up reconcile must classify the new row as MATCHING");
    assertEquals(0, secondReport.drift().size(),
        "no drift expected after a clean apply");
  }

  @Test
  void applyUpdatesDriftedRow() throws Exception {
    // Pre-create the row at a different IP to set up drift.
    final int createStatus = client.createReservation(testMac, testIp, "stale-hostname");
    assertTrue(createStatus == 200 || createStatus == 201);

    final ReservationReconciler reconciler = new ReservationReconciler(client);
    final DesiredReservation want = new DesiredReservation(testMac, testIp, TEST_HOSTNAME);

    final Report report = reconciler.reconcile(List.of(want), Mode.APPLY);
    assertEquals(1, report.drift().size(), "expected one UPDATED");
    assertEquals(Action.UPDATED, report.drift().get(0).action());

    final DhcpReservation now = client.findByMac(testMac).orElseThrow();
    assertEquals(TEST_HOSTNAME, now.hostname(), "hostname should be updated");
    assertEquals(testIp, now.ipaddress());
  }

  @Test
  void extraVsIgnoredFromBboxRow() throws Exception {
    // Pre-create the row so it shows up in the bbox snapshot.
    try {
      client.createReservation(testMac, testIp, TEST_HOSTNAME);
    } catch (Exception ex) {
      throw new AssertionError("setup: failed to seed bbox row", ex);
    }

    // Reconciler with empty desired set + empty ignore list → row classified as EXTRA.
    final ReservationReconciler asExtra = new ReservationReconciler(client);
    final Report extraReport = asExtra.reconcile(List.of(), Mode.DRY_RUN);
    assertTrue(extraReport.extras().stream().anyMatch(o -> o.mac().equalsIgnoreCase(testMac)),
        "test row should appear as EXTRA when not desired");

    // Same shape but the test mac is on the ignore list → row classified as IGNORED.
    final ReservationReconciler asIgnored = new ReservationReconciler(client, List.of(testMac));
    final Report ignoredReport = asIgnored.reconcile(List.of(), Mode.DRY_RUN);
    assertTrue(ignoredReport.ignored().stream().anyMatch(o -> o.mac().equalsIgnoreCase(testMac)),
        "test row should appear as IGNORED when on the ignore list");
    assertFalse(ignoredReport.extras().stream().anyMatch(o -> o.mac().equalsIgnoreCase(testMac)),
        "test row must not double up as EXTRA when ignored");
  }

  @Test
  void countsTallyAcrossActions() throws Exception {
    final ReservationReconciler reconciler = new ReservationReconciler(client);
    final DesiredReservation want = new DesiredReservation(testMac, testIp, TEST_HOSTNAME);

    final Report report = reconciler.reconcile(List.of(want), Mode.DRY_RUN);
    final Map<Action, Integer> counts = report.counts();

    int total = counts.values().stream().mapToInt(Integer::intValue).sum();
    assertEquals(report.outcomes().size(), total,
        "counts must sum to outcomes.size()");
    assertTrue(counts.get(Action.WOULD_CREATE) >= 1,
        "at least one WOULD_CREATE expected (the test row)");
  }
}
