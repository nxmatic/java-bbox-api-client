package io.seedmatic.bbox.reconcile;

import java.util.Locale;
import java.util.Objects;

/**
 * One reservation the consumer wants present on the bbox.
 *
 * <p>Identity is the MAC address (case-insensitive — the bbox normalises to lowercase, so we
 * do too here, defensively against operator-entered uppercase). Inputs are validated minimally:
 * non-blank values, MAC matching {@code aa:bb:cc:dd:ee:ff}-style.
 */
public record DesiredReservation(String mac, String ip, String hostname) {

  public DesiredReservation {
    Objects.requireNonNull(mac, "mac");
    Objects.requireNonNull(ip, "ip");
    Objects.requireNonNull(hostname, "hostname");
    if (mac.isBlank() || ip.isBlank() || hostname.isBlank()) {
      throw new IllegalArgumentException(
          "DesiredReservation requires non-blank mac/ip/hostname; got "
              + "mac='" + mac + "' ip='" + ip + "' hostname='" + hostname + "'");
    }
    if (!mac.matches("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")) {
      throw new IllegalArgumentException(
          "Invalid MAC '" + mac + "' — expected aa:bb:cc:dd:ee:ff form (case-insensitive).");
    }
    mac = mac.toLowerCase(Locale.ROOT);
  }
}
