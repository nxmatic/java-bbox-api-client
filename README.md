# java-bbox-api-client

Java client for the [Bouygues Telecom Bbox][bbox] residential router REST API
(`mabbox.bytel.fr`). Surfaces the subset of endpoints needed to manage static
DHCP reservations declaratively — login, list, create, update, delete.

[bbox]: https://www.bouyguestelecom.fr/

The Bbox API is undocumented publicly; this client encodes the contract
observed against firmware **F@st5696b 25.5.30** (2025-11-14) and validated by a
live CRUD round-trip in CI/test.

## Status

Pre-1.0 (`0.1.0-SNAPSHOT`). The DHCP reservation surface is complete and
covered by a live-router test; other Bbox endpoints (Wi-Fi, voice, port
forwards) are out of scope for now.

## Maven

The library is published to **GitHub Packages**. Add the registry as a
Maven repository and depend on it:

```xml
<repositories>
  <repository>
    <id>github-nxmatic</id>
    <url>https://maven.pkg.github.com/nxmatic/java-bbox-api-client</url>
  </repository>
</repositories>

<dependency>
  <groupId>io.nxmatic</groupId>
  <artifactId>java-bbox-api-client</artifactId>
  <version>0.2.0</version>
</dependency>
```

Add a server entry to `~/.m2/settings.xml` with a GitHub PAT carrying the
`read:packages` scope:

```xml
<servers>
  <server>
    <id>github-nxmatic</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>ghp_YourPersonalAccessTokenWithReadPackagesScope</password>
  </server>
</servers>
```

Note that GitHub Packages requires authentication even for public packages
\(a [known](https://github.com/orgs/community/discussions/26634) GH limitation\).

### Requirements

JDK 25 (uses `java.net.http.HttpClient` and records).

## Usage

The library exposes two layers. Most consumers want the higher-level
**reconciler** — given a list of desired rows, it computes the diff
against the bbox and (optionally) applies the writes. Consumers that
need raw HTTP CRUD use the lower-level **API client** directly.

### Reconciler — declarative `desired` → bbox state

```java
import io.nxmatic.bbox.api.BboxApiClient;
import io.nxmatic.bbox.reconcile.DesiredReservation;
import io.nxmatic.bbox.reconcile.ReservationReconciler;
import io.nxmatic.bbox.reconcile.ReservationReconciler.Mode;
import io.nxmatic.bbox.reconcile.Report;
import java.net.URI;
import java.util.List;

List<DesiredReservation> desired = List.of(
    new DesiredReservation("10:66:6a:4c:00:00", "192.168.1.131", "bioskop-master"),
    new DesiredReservation("10:66:6a:4c:00:01", "192.168.1.132", "bioskop-peer1"));

// MACs the bbox owns that we never touch (TVs, audio devices, manually-added rows).
List<String> ignored = List.of("aa:bb:cc:dd:ee:ff");

try (BboxApiClient bbox =
        BboxApiClient.open(URI.create("https://mabbox.bytel.fr/"), "<admin-password>")) {

  ReservationReconciler reconciler = new ReservationReconciler(bbox, ignored);

  // Dry run: computes the diff, no writes.
  Report preview = reconciler.reconcile(desired, Mode.DRY_RUN);
  System.out.println("Would create: " + preview.created().size());
  System.out.println("Would update: " + preview.drift().size());
  System.out.println("Already correct: " + preview.matching().size());
  System.out.println("Bbox-owned (ignored): " + preview.ignored().size());
  System.out.println("Bbox-extra (unowned): " + preview.extras().size());

  // Apply: same diff, but issues POST/PUT for missing/drifted rows.
  // 'Extra' rows surface in the report but are never deleted (today's contract).
  Report applied = reconciler.reconcile(desired, Mode.APPLY);
  applied.failed().forEach(f ->
      System.err.println("FAILED " + f.mac() + ": " + f.failureMessage().orElse("")));
}
```

Identity is the MAC address (case-insensitive). The reconciler is
single-use: the constructor fetches the bbox table once into a snapshot,
and {@code reconcile} / {@code apply} calls decide against that snapshot.
Create a new reconciler for each pass.

### API client — raw HTTP CRUD

```java
import io.nxmatic.bbox.api.BboxApiClient;
import io.nxmatic.bbox.api.BboxApiClient.DhcpReservation;
import java.net.URI;

try (BboxApiClient bbox =
        BboxApiClient.open(URI.create("https://mabbox.bytel.fr/"), "<admin-password>")) {

  // Read
  for (DhcpReservation row : bbox.listReservations()) {
    System.out.println(row.macaddress() + " -> " + row.ipaddress() + " (" + row.hostname() + ")");
  }

  // Find by MAC
  bbox.findByMac("10:66:6a:4c:00:00")
      .ifPresent(row -> System.out.println("id=" + row.id()));

  // Create / Update / Delete return the HTTP status code.
  int created = bbox.createReservation("02:00:bb:ff:ff:01", "192.168.1.250", "lab-host");  // 201
  int updated = bbox.updateReservation(13, "02:00:bb:ff:ff:01", "192.168.1.251", "lab-host"); // 200
  int deleted = bbox.deleteReservation(13);  // 200
}
```

The session is single-use: it opens an HTTPS session, fetches a CSRF-style
token, and lasts until you `close()` it. The token expires after ~26 minutes
(matching the Bbox firmware's session lifetime), so don't hold a client open
for hours — open one per operation batch.

## Bbox API contract notes

These are the non-obvious bits that fight implementers — captured here so
nobody has to re-discover them.

### Auth

* `POST /api/v1/login` is form-encoded (`password=<pw>`), responds 200 with an
  empty body and `Set-Cookie: BBOX_ID=...; Max-Age=900; Path=/; Version=1; HttpOnly; Secure`.
* JDK's `java.net.CookieManager` renders cookies that arrived with `Version=1`
  in legacy RFC 2965 form (`$Version="1"; BBOX_ID="..."; $Path="/"; ...`) on
  subsequent requests. The Bbox can't parse that and rejects the request. This
  client explicitly builds a plain RFC 6265 `Cookie:` header instead and never
  binds a `CookieManager` to its `HttpClient`.

### Mutating verbs require a `?btoken=<token>` querystring

* `GET /api/v1/device/token` returns `[{"device":{"token":"...","expires":"..."}}]`.
* The Bbox web UI also stores it as a `btoken` cookie alongside `BBOX_ID`, but
  the API itself ignores the cookie and validates the value from the
  **querystring** on every mutating verb. Cookie-only mutations get
  `HTTP 403 {"reason":"Operation requires valid token"}`.
* This client appends `?btoken=...` to every POST/PUT/DELETE on
  `/api/v1/dhcp/clients[/{id}]`.

### Mutation body shape

* `Content-Type: application/x-www-form-urlencoded; charset=UTF-8` (NOT JSON).
* Fields: `enable=1`, `device=<MAC>`, `ipaddress=<ip>`, `ip6address=` (empty),
  `macaddress=<MAC>`, `hostname=<hostname>`. The `device` field duplicates
  `macaddress` — both are required.

### Required headers on mutating verbs

* `Content-Type: application/x-www-form-urlencoded; charset=UTF-8`
* `X-Requested-With: XmlHttpRequest` — note the non-standard mixed-case
  capitalisation; the Bbox UI sends this exact form, and so do we.
* `Origin: <scheme>://<host>` — without trailing slash, even if the configured
  base URI has one.
* `Referer: <baseUri>/dhcp.html` — the Bbox UI's referer; matters for the
  same-origin check.

### Two distinct tables

* `/api/v1/dhcp/clients` — the static-reservation table (this is what we
  manage).
* `/api/v1/hosts` — the device-discovery table (every MAC the Bbox has ever
  seen). Read-only context for callers; mutating it does NOT change DHCP
  behaviour.

The two have separate ID spaces — the same MAC may have completely different
IDs in the two views.

### "200 with no effect" on unsupported shapes

The Bbox returns `200 OK Content-Length: 0` for verbs/bodies it doesn't
understand, instead of 4xx. After every mutation, callers that need certainty
should follow up with a `findByMac()` to confirm the row actually changed.

## Running the live test

The test in `src/test/java/io/nxmatic/bbox/BboxApiClientLiveTest.java`
performs a full CRUD round-trip against the actual Bbox on the LAN. To run
it, you need:

1. A clone of this repo with the `sops-yaml` smudge filter active (the
   worktree `.secrets` must be plaintext YAML, not encrypted).
2. The `.secrets` file populated with the bbox URI and admin password:
   ```yaml
   uri: https://mabbox.bytel.fr/
   # sops:encrypted
   password: <your-bbox-admin-password>
   ```
3. A test MAC and IP that DON'T collide with anything on your LAN. The
   defaults are `02:00:bb:ff:ff:01` / `192.168.1.250` (the `02:` prefix is
   IETF-reserved for locally-administered MACs). Override via env vars if
   they collide:
   ```sh
   BBOX_TEST_MAC=02:00:bb:ff:ff:42 BBOX_TEST_IP=192.168.1.249 mvn test
   ```

Then:

```sh
flox activate
mvn test
```

The test:

* Logs into the Bbox.
* Deletes any stale row at the test MAC (in case a previous run failed
  before cleanup).
* Creates the test row, asserts `201 Created` and that the row is visible.
* Updates the hostname, asserts the change persisted.
* Updates the IP, asserts the change persisted.
* Deletes the row, asserts it's gone.
* In `@AfterEach`, best-effort deletes the test row regardless of outcome.

## Building locally without flox

The repo includes a maven wrapper. With JDK 25 and Maven on PATH:

```sh
./mvnw test
```

With flox (recommended — pins both):

```sh
flox activate
mvn test
```

## License

MIT.
