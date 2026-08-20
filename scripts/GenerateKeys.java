import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

/**
 * Generates the RSA key pair that psp-service signs JWTs with.
 *
 * <p>Run directly with the JDK, no compilation step and no build tool:
 *
 * <pre>
 *   java scripts/GenerateKeys.java psp-service/src/main/resources/keys
 * </pre>
 *
 * <h3>Why Java and not openssl</h3>
 *
 * The first version of this shelled out to {@code openssl}, which is present on
 * this machine only because Git for Windows bundles it. That is an accident of
 * what happens to be installed, not a dependency the project declares — and it
 * meant the bootstrap step worked in Git Bash and failed in PowerShell.
 *
 * <p>A JDK is already a hard requirement: nothing in this platform runs
 * without one. Using it here means the same command works from any shell on any
 * OS, and there is one fewer tool a new contributor has to have.
 *
 * <h3>Why these two formats specifically</h3>
 *
 * {@code JwkConfig} reads the private key with {@link PKCS8EncodedKeySpec} and
 * the public key with {@link X509EncodedKeySpec}. Java's
 * {@code getEncoded()} produces exactly those encodings, so the PEM bodies here
 * are what those classes expect.
 *
 * <p>This is worth knowing because {@code openssl genrsa} does <em>not</em>
 * produce it: its default is PKCS#1 (<code>BEGIN RSA PRIVATE KEY</code>), which
 * Java cannot read without conversion. Getting that wrong yields an
 * {@code InvalidKeySpecException} at startup that says very little about the
 * actual problem.
 */
public class GenerateKeys {

    private static final int KEY_SIZE = 2048;

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0
                ? args[0]
                : "psp-service/src/main/resources/keys");

        Path privatePath = dir.resolve("private_key_pkcs8.pem");
        Path publicPath = dir.resolve("public_key.pem");

        if (Files.exists(privatePath)) {
            System.out.println("Keys already exist in " + dir.toAbsolutePath());
            System.out.println("Leaving them alone. Delete them first to rotate.");
            return;
        }

        Files.createDirectories(dir);

        System.out.println("Generating RSA-" + KEY_SIZE + " signing key in " + dir.toAbsolutePath());

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        // SecureRandom explicitly, not the default: this key signs the tokens
        // that authorise money movement, and predictable key material would
        // let anyone mint one.
        generator.initialize(KEY_SIZE, SecureRandom.getInstanceStrong());
        KeyPair pair = generator.generateKeyPair();

        // getEncoded() gives PKCS#8 for a private key and X.509
        // SubjectPublicKeyInfo for a public key -- precisely what
        // PKCS8EncodedKeySpec and X509EncodedKeySpec consume.
        writePem(privatePath, "PRIVATE KEY", pair.getPrivate().getEncoded());
        writePem(publicPath, "PUBLIC KEY", pair.getPublic().getEncoded());

        restrictPermissions(privatePath);

        System.out.println("    private_key_pkcs8.pem  (gitignored -- keep it that way)");
        System.out.println("    public_key.pem");
        System.out.println();
        System.out.println("Both are excluded by psp-service/.gitignore. Do not commit either.");
    }

    /** PEM: base64 of the DER bytes, wrapped at 64 columns, between markers. */
    private static void writePem(Path path, String label, byte[] der) throws IOException {
        String body = Base64.getMimeEncoder(64, System.lineSeparator().getBytes())
                .encodeToString(der);

        String pem = "-----BEGIN " + label + "-----" + System.lineSeparator()
                + body + System.lineSeparator()
                + "-----END " + label + "-----" + System.lineSeparator();

        Files.writeString(path, pem);
    }

    /**
     * Best-effort tightening of the private key file.
     *
     * <p>Deliberately not fatal on failure. Windows ACLs do not map onto POSIX
     * permissions, and refusing to produce a working development key because
     * the file mode could not be set would trade a real problem for a
     * theoretical one. In a real deployment this key would not be a file at
     * all.
     */
    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path,
                    PosixFilePermissionsHolder.OWNER_READ_WRITE);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: no POSIX permissions. Expected.
        }
    }

    /** Held separately so the POSIX class is only loaded where it applies. */
    private static final class PosixFilePermissionsHolder {
        static final java.util.Set<java.nio.file.attribute.PosixFilePermission> OWNER_READ_WRITE =
                java.util.EnumSet.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
    }
}
