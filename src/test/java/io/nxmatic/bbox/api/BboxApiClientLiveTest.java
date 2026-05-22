package io.nxmatic.bbox.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.bbox.api.BboxApiClient.DhcpReservation;
import io.nxmatic.bbox.test.BboxSecrets;
import io.nxmatic.bbox.test.BboxSecrets.Coordinates;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end live-router test for {@link BboxApiClient}.
 *
 * <p>Reads coordinates from the repo's {@code .secrets} (sops-managed). Mutates a single
 * pre-chosen test reservation; never touches any other row.
 *
 * <p>Safety guards:
 *
 * <ul>
 *   <li>A locally-administered test MAC ({@code 02:00:bb:ff:fe:01}) is used so it cannot
 *       collide with a real device's BIA. Distinct from the reconciler test's MAC so the two
 *       suites don't race on the same row across back-to-back surefire runs.
 *   <li>If the test row is already present at start (e.g. a previous run failed before
 *       cleanup), the test deletes it and retries once. If still present, it skips.
 *   <li>{@link AfterEach} best-effort deletes the test row so re-runs don't accumulate.
 * </ul>
 *
 * <p>Override the test MAC/IP via env vars {@code BBOX_TEST_MAC} / {@code BBOX_TEST_IP} when the
 * defaults collide with something on your LAN.
 */
final class BboxApiClientLiveTest {

  private static final String DEFAULT_TEST_MAC = "02:00:bb:ff:fe:01";
  private static final String DEFAULT_TEST_IP = "192.168.1.249";
  private static final String TEST_HOSTNAME = "java-bbox-api-client-test";
  private static final String TEST_HOSTNAME_RENAMED = "java-bbox-api-client-test-renamed";

  private final String testMac =
      System.getenv().getOrDefault("BBOX_TEST_MAC", DEFAULT_TEST_MAC);
  private final String testIp =
      System.getenv().getOrDefault("BBOX_TEST_IP", DEFAULT_TEST_IP);
  private final String testIpAlt =
      System.getenv().getOrDefault("BBOX_TEST_IP_ALT", "192.168.1.248");

  private BboxApiClient client;

  @BeforeEach
  void openSession() throws Exception {
    final Coordinates secrets = BboxSecrets.read();
    this.client = BboxApiClient.open(secrets.uri(), secrets.password());

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
  void crudRoundTrip() throws Exception {
    // CREATE
    final int createStatus = client.createReservation(testMac, testIp, TEST_HOSTNAME);
    assertTrue(
        createStatus == 200 || createStatus == 201,
        "POST should return 200 or 201, got " + createStatus);
    final DhcpReservation created = waitForPresent("row must be visible after CREATE");
    assertEquals(testIp, created.ipaddress(), "CREATE persisted ip");
    assertEquals(TEST_HOSTNAME, created.hostname(), "CREATE persisted hostname");

    final int id = created.id();

    // UPDATE hostname only — bbox occasionally rejects a PUT issued <100ms after the matching
    // POST with HTTP 400; retry briefly to ride out that window.
    final int updateHostnameStatus =
        retryUntilSuccess("PUT (hostname)",
            () -> client.updateReservation(id, testMac, testIp, TEST_HOSTNAME_RENAMED));
    assertEquals(200, updateHostnameStatus, "PUT (hostname) should return 200");
    final DhcpReservation afterRename = waitForHostname(TEST_HOSTNAME_RENAMED);
    assertEquals(TEST_HOSTNAME_RENAMED, afterRename.hostname(), "UPDATE persisted hostname");
    assertEquals(testIp, afterRename.ipaddress(), "UPDATE preserved ip when only hostname changed");

    // UPDATE ip
    final int updateIpStatus =
        retryUntilSuccess("PUT (ip)",
            () -> client.updateReservation(id, testMac, testIpAlt, TEST_HOSTNAME_RENAMED));
    assertEquals(200, updateIpStatus, "PUT (ip) should return 200");
    final DhcpReservation afterIpChange = waitForIp(testIpAlt);
    assertEquals(testIpAlt, afterIpChange.ipaddress(), "UPDATE persisted new ip");

    // DELETE
    final int deleteStatus = retryUntilSuccess("DELETE", () -> client.deleteReservation(id));
    assertEquals(200, deleteStatus, "DELETE should return 200");
    waitForAbsent("row must be gone after DELETE");
  }

  // Retry helpers -- the bbox sometimes rejects rapid PUT/DELETE-after-POST with a transient
  // HTTP 400 ("invalid request"), and findByMac sees stale snapshots for ~100-300ms.  These
  // helpers back off briefly to ride out the inconsistency.  Caps at ~1.4s wall-clock total.

  @FunctionalInterface
  private interface ApiCall {
    int call() throws Exception;
  }

  private int retryUntilSuccess(String label, ApiCall call) throws Exception {
    int status = 0;
    for (int attempt = 0; attempt < 5; attempt++) {
      status = call.call();
      if (status == 200 || status == 201) {
        return status;
      }
      Thread.sleep(150L * (attempt + 1));
    }
    return status; // last status; caller asserts on it.
  }

  private DhcpReservation waitForPresent(String label) throws Exception {
    for (int attempt = 0; attempt < 5; attempt++) {
      final Optional<DhcpReservation> hit = client.findByMac(testMac);
      if (hit.isPresent()) {
        return hit.get();
      }
      Thread.sleep(150L * (attempt + 1));
    }
    throw new AssertionError(label);
  }

  private DhcpReservation waitForHostname(String expected) throws Exception {
    DhcpReservation last = null;
    for (int attempt = 0; attempt < 5; attempt++) {
      final Optional<DhcpReservation> hit = client.findByMac(testMac);
      if (hit.isPresent()) {
        last = hit.get();
        if (expected.equals(last.hostname())) {
          return last;
        }
      }
      Thread.sleep(150L * (attempt + 1));
    }
    throw new AssertionError(
        "row never settled to hostname=" + expected + "; last=" + last);
  }

  private DhcpReservation waitForIp(String expected) throws Exception {
    DhcpReservation last = null;
    for (int attempt = 0; attempt < 5; attempt++) {
      final Optional<DhcpReservation> hit = client.findByMac(testMac);
      if (hit.isPresent()) {
        last = hit.get();
        if (expected.equals(last.ipaddress())) {
          return last;
        }
      }
      Thread.sleep(150L * (attempt + 1));
    }
    throw new AssertionError("row never settled to ip=" + expected + "; last=" + last);
  }

  private void waitForAbsent(String label) throws Exception {
    for (int attempt = 0; attempt < 5; attempt++) {
      if (client.findByMac(testMac).isEmpty()) {
        return;
      }
      Thread.sleep(150L * (attempt + 1));
    }
    throw new AssertionError(label);
  }
}
