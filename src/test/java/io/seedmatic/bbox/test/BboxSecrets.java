package io.seedmatic.bbox.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test-only helper to read the bbox endpoint coordinates from the repo's {@code .secrets}.
 *
 * <p>The {@code .secrets} file is sops-encrypted at rest but the repo's {@code .gitattributes}
 * declares a {@code filter=sops-yaml} smudge filter, so the worktree copy is plaintext during
 * normal operation. If we see a top-level {@code sops:} key we raise — better to fail loud than
 * accidentally pass an {@code ENC[...]} blob to the live router.
 *
 * <p>Schema (worktree plaintext):
 *
 * <pre>
 * uri: https://mabbox.bytel.fr/
 * # sops:encrypted
 * password: ...
 * </pre>
 */
public final class BboxSecrets {

  /** Coordinates returned together to avoid two passes over the YAML. */
  public record Coordinates(URI uri, String password) {}

  /** Repo-relative path to the secrets file (resolved against the working directory). */
  public static final Path SECRETS_PATH = Paths.get(".secrets");

  private BboxSecrets() {}

  public static Coordinates read() {
    if (!Files.isReadable(SECRETS_PATH)) {
      throw new IllegalStateException(
          "Cannot read " + SECRETS_PATH.toAbsolutePath()
              + " — is the sops-yaml smudge filter active?");
    }

    final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    final ObjectNode root;
    try {
      root = (ObjectNode) mapper.readTree(SECRETS_PATH.toFile());
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to parse YAML from " + SECRETS_PATH, ex);
    }
    if (root.has("sops")) {
      throw new IllegalStateException(
          SECRETS_PATH + " appears to be sops-encrypted at rest; the worktree copy must be "
              + "plaintext (check that .gitattributes declares `filter=sops-yaml` and the "
              + "smudge ran).");
    }

    final String rawUri = readScalar(root, "uri");
    final URI uri;
    try {
      uri = URI.create(rawUri);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("Invalid URI in " + SECRETS_PATH + ": " + rawUri, ex);
    }
    return new Coordinates(uri, readScalar(root, "password"));
  }

  private static String readScalar(JsonNode root, String key) {
    if (!root.has(key) || root.get(key).isNull() || !root.get(key).isTextual()) {
      throw new IllegalStateException(
          "Missing or non-string '" + key + "' in " + SECRETS_PATH);
    }
    final String value = root.get(key).asText().trim();
    if (value.isEmpty()) {
      throw new IllegalStateException("Empty '" + key + "' in " + SECRETS_PATH);
    }
    return value;
  }
}
