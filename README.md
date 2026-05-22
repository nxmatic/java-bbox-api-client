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

The library is published to two registries — pick whichever fits your
build setup. Both serve the exact same JAR; the JitPack route is more
convenient (no auth, no settings.xml) but adds JitPack as a third-party
build-time dependency.

### Option 1 — JitPack (no auth)

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.nxmatic</groupId>
  <artifactId>java-bbox-api-client</artifactId>
  <version>v0.1.0</version>
</dependency>
```

JitPack builds on demand the first time a consumer requests the tag, then
caches forever. First fetch can take 1-3 minutes; subsequent fetches are
instant.

### Option 2 — GitHub Packages (auth required)

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
  <version>0.1.0</version>
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

Note GitHub Packages requires authentication even for public packages; this
is a [known](https://github.com/orgs/community/discussions/26634) GH
limitation, not a configuration issue.

### Requirements

JDK 25 (uses `java.net.http.HttpClient` and records).

## Usage

```java
import io.nxmatic.bbox.BboxApiClient;
import io.nxmatic.bbox.BboxApiClient.DhcpReservation;
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

  // Create
  int created = bbox.createReservation("02:00:bb:ff:ff:01", "192.168.1.250", "lab-host");
  // 201 Created on success

  // Update
  int updated = bbox.updateReservation(13, "02:00:bb:ff:ff:01", "192.168.1.251", "lab-host");
  // 200 OK on success

  // Delete
  int deleted = bbox.deleteReservation(13);
  // 200 OK on success
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
