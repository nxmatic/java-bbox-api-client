package io.seedmatic.bbox.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Java client for the Bouygues Bbox router REST API.
 *
 * <p>Surfaces the subset of endpoints we need to manage static DHCP reservations:
 *
 * <ul>
 *   <li>{@code POST /api/v1/login} — form-encoded {@code password=…}; sets a {@code BBOX_ID}
 *       session cookie.
 *   <li>{@code GET /api/v1/device/token} — fetches a CSRF-style {@code btoken} that the bbox
 *       web UI ships back as a cookie alongside {@code BBOX_ID}. Required for mutating verbs
 *       on F@st5696b firmware (25.5.30): without it, POST/PUT/DELETE silently no-op (return
 *       {@code 200 OK Content-Length: 0} but make no change).
 *   <li>{@code GET/POST/PUT/DELETE /api/v1/dhcp/clients[/{id}]} — the static-reservation table.
 * </ul>
 *
 * <p>Mutation requests mirror the bbox web UI exactly: form-urlencoded body, {@code
 * X-Requested-With: XmlHttpRequest} (note the non-standard mixed-case spelling that the UI
 * sends), and {@code Origin}/{@code Referer} matching the UI's same-origin context.
 *
 * <p>TLS verification is disabled because the bbox certificate's CN is {@code mabbox.bytel.fr}
 * but operators may legitimately reach it via the LAN IP. The threat model is the LAN itself;
 * the credential is per-router.
 */
public final class BboxApiClient implements AutoCloseable {

  /** Reservation row returned by {@code GET /api/v1/dhcp/clients}. */
  public record DhcpReservation(int id, String hostname, String macaddress, String ipaddress) {}

  private final URI baseUri;
  private final HttpClient httpClient;
  private final Map<String, String> cookies = new LinkedHashMap<>();
  private final Gson gson = new Gson();
  /** CSRF-style token issued by {@code GET /api/v1/device/token}; required on mutating verbs. */
  private String btoken = "";

  private BboxApiClient(URI baseUri, HttpClient httpClient) {
    this.baseUri = baseUri;
    this.httpClient = httpClient;
  }

  /**
   * Open a session against the supplied bbox base URI (e.g. {@code https://mabbox.bytel.fr/}).
   *
   * <p>The {@link CookieManager} is used purely as an in-memory store — it is intentionally
   * <em>not</em> bound to the {@link HttpClient}. JDK's CookieManager renders {@code
   * Set-Cookie: ...; Version=1} cookies in legacy RFC 2965 form ({@code $Version="1"; X="...";
   * $Path="/"; ...}) which the bbox rejects. We render our own plain {@code Cookie:} header on
   * every authenticated request via {@link #authenticatedRequest(URI)}.
   */
  public static BboxApiClient open(URI baseUri, String adminPassword) throws Exception {
    final HttpClient httpClient =
        HttpClient.newBuilder()
            .version(Version.HTTP_1_1)
            .followRedirects(Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .sslContext(insecureSslContext())
            .build();

    final BboxApiClient client = new BboxApiClient(baseUri, httpClient);
    client.login(adminPassword);
    return client;
  }

  private void login(String password) throws Exception {
    final String body = "password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
    final HttpRequest request =
        HttpRequest.newBuilder(baseUri.resolve("/api/v1/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(15))
            .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

    final HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new BboxApiException(
          "POST /api/v1/login returned HTTP "
              + response.statusCode()
              + " (expected 200). Body: "
              + response.body());
    }
    captureSetCookies(response);
    if (!hasCookie("BBOX_ID")) {
      throw new BboxApiException(
          "POST /api/v1/login did not set a BBOX_ID cookie; authentication failed.");
    }
    fetchAndStoreBtoken();
  }

  /**
   * Extract every {@code Set-Cookie} from the response and store the name/value pair (only).
   * We deliberately ignore attributes ({@code Version}, {@code Domain}, {@code Path}, {@code
   * Secure}, {@code HttpOnly}, etc.) — those are JVM-side bookkeeping; the bbox only cares
   * about the literal {@code name=value} pair when it sees the next {@code Cookie:} header.
   */
  private void captureSetCookies(HttpResponse<?> response) {
    response.headers().allValues("set-cookie").forEach(this::storeRawCookie);
  }

  private void storeRawCookie(String setCookieValue) {
    // "name=value; Max-Age=...; Path=/; ..." — keep only the leading name=value pair.
    final int semi = setCookieValue.indexOf(';');
    final String pair = semi < 0 ? setCookieValue : setCookieValue.substring(0, semi);
    final int eq = pair.indexOf('=');
    if (eq <= 0) {
      return;
    }
    final String name = pair.substring(0, eq).trim();
    final String value = pair.substring(eq + 1).trim();
    storeCookie(name, value);
  }

  /**
   * Fetch the CSRF-style {@code btoken} from {@code GET /api/v1/device/token}.
   *
   * <p>The bbox web UI also stores it as a {@code btoken} cookie, but the API itself validates
   * the token from the querystring on mutating verbs ({@code POST/PUT/DELETE} on {@code
   * /api/v1/dhcp/clients[/{id}]?btoken=...}). Cookies-only requests are rejected with HTTP
   * 403 {@code "Operation requires valid token"}.
   */
  private void fetchAndStoreBtoken() throws Exception {
    final HttpRequest request =
        authenticatedRequest(baseUri.resolve("/api/v1/device/token")).GET().build();
    final HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new BboxApiException(
          "GET /api/v1/device/token returned HTTP " + response.statusCode());
    }
    // Response shape: [{ "device": { "token": "...", "now": "...", "expires": "..." } }]
    final JsonArray outer = gson.fromJson(response.body(), JsonArray.class);
    if (outer == null || outer.isEmpty()) {
      throw new BboxApiException(
          "GET /api/v1/device/token returned empty array; cannot mutate without btoken.");
    }
    final JsonObject first = outer.get(0).getAsJsonObject();
    final JsonObject device = first.getAsJsonObject("device");
    if (device == null || !device.has("token")) {
      throw new BboxApiException("GET /api/v1/device/token response missing device.token");
    }
    this.btoken = device.get("token").getAsString();
  }

  private void storeCookie(String name, String value) {
    cookies.put(name, value);
  }

  private boolean hasCookie(String name) {
    return cookies.containsKey(name);
  }

  /**
   * Build a request that explicitly carries every cookie in the store as a {@code Cookie:}
   * header.
   *
   * <p>Java's {@link HttpClient} <em>should</em> auto-attach matching cookies via the bound
   * {@link CookieManager}, but in practice (observed on JDK 25 Zulu CA) it sometimes drops
   * cookies whose {@code Set-Cookie} header carried a {@code Version=1} attribute (RFC 2965
   * style) on subsequent requests. The bbox sends {@code BBOX_ID=...; ...; Version=1; HttpOnly;
   * Secure}, so we hit that case. Attaching the {@code Cookie:} header ourselves bypasses the
   * inconsistency entirely.
   */
  private HttpRequest.Builder authenticatedRequest(URI uri) {
    final HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15));
    final String cookieHeader = renderCookieHeader();
    if (!cookieHeader.isEmpty()) {
      builder.header("Cookie", cookieHeader);
    }
    return builder;
  }

  private String renderCookieHeader() {
    if (cookies.isEmpty()) {
      return "";
    }
    final StringBuilder sb = new StringBuilder();
    cookies.forEach(
        (name, value) -> {
          if (sb.length() > 0) {
            sb.append("; ");
          }
          sb.append(name).append('=').append(value);
        });
    return sb.toString();
  }

  /** Fetch all current static reservations from {@code /api/v1/dhcp/clients}. */
  public List<DhcpReservation> listReservations() throws Exception {
    final HttpRequest request =
        authenticatedRequest(baseUri.resolve("/api/v1/dhcp/clients")).GET().build();

    final HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new BboxApiException(
          "GET /api/v1/dhcp/clients returned HTTP " + response.statusCode());
    }

    // Response shape: [{ "dhcp": { "clients": [ {id, hostname, macaddress, ipaddress, ...} ] } }]
    final JsonArray outer = gson.fromJson(response.body(), JsonArray.class);
    if (outer == null || outer.isEmpty()) {
      return List.of();
    }
    final JsonObject first = outer.get(0).getAsJsonObject();
    final JsonObject dhcp = first.getAsJsonObject("dhcp");
    if (dhcp == null) {
      return List.of();
    }
    final JsonArray clients = dhcp.getAsJsonArray("clients");
    if (clients == null) {
      return List.of();
    }

    final List<DhcpReservation> out = new ArrayList<>(clients.size());
    for (int i = 0; i < clients.size(); i++) {
      final JsonObject obj = clients.get(i).getAsJsonObject();
      out.add(
          new DhcpReservation(
              obj.has("id") ? obj.get("id").getAsInt() : -1,
              optString(obj, "hostname").orElse(""),
              optString(obj, "macaddress").orElse(""),
              optString(obj, "ipaddress").orElse("")));
    }
    return List.copyOf(out);
  }

  /** Find a reservation by MAC address (case-insensitive). */
  public Optional<DhcpReservation> findByMac(String mac) throws Exception {
    final String needle = mac.toLowerCase(java.util.Locale.ROOT);
    return listReservations().stream()
        .filter(row -> row.macaddress() != null
            && row.macaddress().toLowerCase(java.util.Locale.ROOT).equals(needle))
        .findFirst();
  }

  /**
   * Create a new reservation via {@code POST /api/v1/dhcp/clients}. Returns the HTTP status code.
   * Per the bbox contract, callers should follow up with {@link #findByMac(String)} to confirm
   * the row actually persisted (the bbox returns {@code 200 OK} for unsupported shapes too).
   *
   * <p>Transparently retries on transient HTTP {@code 400} — see {@link #sendMutation}.
   */
  public int createReservation(String mac, String ip, String hostname) throws Exception {
    return sendMutation(() ->
        mutationRequest(baseUri.resolve("/api/v1/dhcp/clients"))
            .POST(BodyPublishers.ofString(reservationFormBody(mac, ip, hostname),
                StandardCharsets.UTF_8))
            .build());
  }

  /**
   * Update an existing reservation via {@code PUT /api/v1/dhcp/clients/{id}}.
   * Transparently retries on transient HTTP {@code 400} — see {@link #sendMutation}.
   */
  public int updateReservation(int id, String mac, String ip, String hostname) throws Exception {
    return sendMutation(() ->
        mutationRequest(baseUri.resolve("/api/v1/dhcp/clients/" + id))
            .PUT(BodyPublishers.ofString(reservationFormBody(mac, ip, hostname),
                StandardCharsets.UTF_8))
            .build());
  }

  /**
   * Delete a reservation via {@code DELETE /api/v1/dhcp/clients/{id}}.
   * Transparently retries on transient HTTP {@code 400} — see {@link #sendMutation}.
   */
  public int deleteReservation(int id) throws Exception {
    return sendMutation(() ->
        mutationRequest(baseUri.resolve("/api/v1/dhcp/clients/" + id)).DELETE().build());
  }

  /**
   * Send a mutating request, retrying on transient HTTP 400 from the bbox.
   *
   * <p>The Bbox firmware F@st5696b 25.5.30 sometimes rejects POST/PUT/DELETE issued shortly
   * after a recent mutation on the same MAC with HTTP 400 ("invalid request"), apparently
   * because the bbox's internal index hasn't settled.  Re-issuing the same request 200-600ms
   * later succeeds.  We retry up to 3 times with backoff so consumers don't have to.
   *
   * <p>Other 4xx/5xx pass through unchanged — only 400 is treated as transient.
   */
  private int sendMutation(MutationRequestSupplier requestSupplier) throws Exception {
    int status = 0;
    for (int attempt = 0; attempt < 3; attempt++) {
      final HttpRequest request = requestSupplier.build();
      status = httpClient.send(request, BodyHandlers.ofString()).statusCode();
      if (status != 400) {
        return status;
      }
      // 200ms, 400ms — caller sees ≤600ms total wall-clock from one transient 400.
      Thread.sleep(200L * (attempt + 1));
    }
    return status;
  }

  @FunctionalInterface
  private interface MutationRequestSupplier {
    HttpRequest build();
  }

  /**
   * Build a request matching what the bbox web UI sends for mutating endpoints — required by the
   * API on F@st5696b firmware (25.5.30) to actually persist changes:
   *
   * <ul>
   *   <li>{@code Content-Type: application/x-www-form-urlencoded; charset=UTF-8}
   *   <li>{@code X-Requested-With: XmlHttpRequest} (note the capitalisation; the UI uses this exact form)
   *   <li>{@code Origin} and {@code Referer: /dhcp.html} so the request is treated as same-origin
   * </ul>
   */
  private HttpRequest.Builder mutationRequest(URI uri) {
    return authenticatedRequest(withBtoken(uri))
        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        .header("X-Requested-With", "XmlHttpRequest")
        .header("Origin", originHeader())
        .header("Referer", baseUri.resolve("/dhcp.html").toString());
  }

  /**
   * Append {@code ?btoken=<token>} (or {@code &btoken=...} if the URI already has a query
   * string) to the supplied URI. Required for the bbox to accept mutating verbs on {@code
   * /api/v1/dhcp/clients}.
   */
  private URI withBtoken(URI uri) {
    if (btoken.isEmpty()) {
      return uri;
    }
    final String query = uri.getRawQuery();
    final String encoded =
        URLEncoder.encode(btoken, StandardCharsets.UTF_8).replace("+", "%20");
    final String suffix = (query == null || query.isEmpty()) ? "?btoken=" : "&btoken=";
    return URI.create(uri + suffix + encoded);
  }

  /**
   * Render the {@code Origin} header value. The bbox web UI sends scheme + host (no trailing
   * slash, no path); the bbox itself rejects requests where {@code Origin} doesn't match that
   * shape with HTTP 403, so we normalise the configured base URI here regardless of how it was
   * provided.
   */
  private String originHeader() {
    final StringBuilder sb = new StringBuilder();
    sb.append(baseUri.getScheme()).append("://").append(baseUri.getHost());
    if (baseUri.getPort() > 0) {
      sb.append(':').append(baseUri.getPort());
    }
    return sb.toString();
  }

  private String reservationFormBody(String mac, String ip, String hostname) {
    final StringBuilder sb = new StringBuilder(160);
    appendField(sb, "enable", "1", false);
    appendField(sb, "device", mac, true);
    appendField(sb, "ipaddress", ip, true);
    appendField(sb, "ip6address", "", true);
    appendField(sb, "macaddress", mac, true);
    appendField(sb, "hostname", hostname, true);
    return sb.toString();
  }

  private static void appendField(StringBuilder sb, String key, String value, boolean leadingAmp) {
    if (leadingAmp) {
      sb.append('&');
    }
    sb.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
  }

  private static Optional<String> optString(JsonObject obj, String field) {
    if (!obj.has(field) || obj.get(field).isJsonNull()) {
      return Optional.empty();
    }
    return Optional.of(obj.get(field).getAsString());
  }

  /**
   * SSL context that trusts every certificate. Used because the bbox cert is signed for {@code
   * mabbox.bytel.fr} but we may legitimately connect via the LAN IP {@code 192.168.1.254},
   * where strict CN validation fails. The threat model is the LAN itself.
   */
  private static SSLContext insecureSslContext() throws Exception {
    final SSLContext sslContext = SSLContext.getInstance("TLS");
    final TrustManager[] trustAll =
        new TrustManager[] {
          new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
              return new X509Certificate[0];
            }
          }
        };
    sslContext.init(null, trustAll, new SecureRandom());
    return sslContext;
  }

  @Override
  public void close() {
    cookies.clear();
  }

  /** Thrown when the bbox returns an unexpected status or shape. */
  public static final class BboxApiException extends RuntimeException {
    public BboxApiException(String message) {
      super(message);
    }

    public BboxApiException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
