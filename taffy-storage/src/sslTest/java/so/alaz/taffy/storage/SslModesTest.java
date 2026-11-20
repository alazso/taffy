package so.alaz.taffy.storage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Connects to MySQL, MariaDB and PostgreSQL servers that serve TLS with a certificate for {@code localhost} only,
 * issued by a certificate authority this class creates and puts in the JVM's default trust store. Each connection
 * uses the URL {@link Storage#serverUrl} builds for the mode under test.
 *
 * <p>Runs in the {@code sslTest} task, in a JVM of its own, because the default trust store is read once per JVM.
 */
final class SslModesTest {

    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final String CERTS = "/certs/";

    @TempDir
    static Path keys;

    private static final Map<Dialect, JdbcDatabaseContainer<?>> SERVERS = new EnumMap<>(Dialect.class);

    @BeforeAll
    static void startServers() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        createCertificates();
        System.setProperty("javax.net.ssl.trustStore", keys.resolve("trust.p12").toString());
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
        System.setProperty("javax.net.ssl.trustStorePassword", new String(PASSWORD));

        String ca = Files.readString(keys.resolve("ca.pem"));
        String cert = Files.readString(keys.resolve("server.pem"));
        String key = Files.readString(keys.resolve("server-key.pem"));

        MySQLContainer<?> mysql = new MySQLContainer<>(DatabaseImages.MYSQL);
        MariaDBContainer<?> mariadb = new MariaDBContainer<>(DatabaseImages.MARIADB);
        for (JdbcDatabaseContainer<?> server : List.<JdbcDatabaseContainer<?>>of(mysql, mariadb)) {
            server.withCopyToContainer(Transferable.of(ca, 0644), CERTS + "ca.pem")
                .withCopyToContainer(Transferable.of(cert, 0644), CERTS + "server.pem")
                .withCopyToContainer(Transferable.of(key, 0644), CERTS + "server-key.pem")
                .withCommand("--ssl-ca=" + CERTS + "ca.pem", "--ssl-cert=" + CERTS + "server.pem",
                    "--ssl-key=" + CERTS + "server-key.pem");
        }
        // PostgreSQL reads a key file only when the server's own user owns it and nobody else can read it.
        String postgresStart = "cp " + CERTS + "server.pem " + CERTS + "server-key.pem /var/lib/postgresql/"
            + " && chown postgres:postgres /var/lib/postgresql/server.pem /var/lib/postgresql/server-key.pem"
            + " && chmod 600 /var/lib/postgresql/server-key.pem"
            + " && exec docker-entrypoint.sh postgres -c fsync=off -c ssl=on"
            + " -c ssl_cert_file=/var/lib/postgresql/server.pem -c ssl_key_file=/var/lib/postgresql/server-key.pem";
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DatabaseImages.POSTGRES)
            .withCopyToContainer(Transferable.of(cert, 0644), CERTS + "server.pem")
            .withCopyToContainer(Transferable.of(key, 0644), CERTS + "server-key.pem")
            .withCreateContainerCmdModifier(create -> create.withEntrypoint("sh", "-c", postgresStart));

        SERVERS.put(Dialect.MYSQL, mysql);
        SERVERS.put(Dialect.MARIADB, mariadb);
        SERVERS.put(Dialect.POSTGRES, postgres);
        SERVERS.values().parallelStream().forEach(JdbcDatabaseContainer::start);
        for (JdbcDatabaseContainer<?> server : SERVERS.values()) {
            assumeTrue(server.getHost().equals("localhost"),
                "the database containers are not reached as localhost: " + server.getHost());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"MYSQL", "MARIADB", "POSTGRES"})
    void disableConnectsWithoutEncryption(Dialect dialect) throws SQLException {
        try (Connection connection = connect(dialect, "localhost", StorageSettings.SslMode.DISABLE)) {
            assertThat(encrypted(dialect, connection)).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"MYSQL", "MARIADB", "POSTGRES"})
    void trustConnectsEncrypted(Dialect dialect) throws SQLException {
        try (Connection connection = connect(dialect, "localhost", StorageSettings.SslMode.TRUST)) {
            assertThat(encrypted(dialect, connection)).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"MYSQL", "MARIADB", "POSTGRES"})
    void trustAcceptsACertificateForAnotherHostName(Dialect dialect) throws SQLException {
        try (Connection connection = connect(dialect, "127.0.0.1", StorageSettings.SslMode.TRUST)) {
            assertThat(encrypted(dialect, connection)).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"MYSQL", "MARIADB", "POSTGRES"})
    void verifyFullConnectsEncryptedWithACertificateTheTrustStoreAcceptsForTheHostName(Dialect dialect)
        throws SQLException {
        try (Connection connection = connect(dialect, "localhost", StorageSettings.SslMode.VERIFY_FULL)) {
            assertThat(encrypted(dialect, connection)).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"MYSQL", "MARIADB", "POSTGRES"})
    void verifyFullRefusesACertificateForAnotherHostName(Dialect dialect) {
        assertThatThrownBy(() -> connect(dialect, "127.0.0.1", StorageSettings.SslMode.VERIFY_FULL).close())
            .isInstanceOf(SQLException.class);
    }

    private static Connection connect(Dialect dialect, String host, StorageSettings.SslMode mode)
        throws SQLException {
        JdbcDatabaseContainer<?> server = SERVERS.get(dialect);
        StorageSettings.Server settings = StorageSettings.Server.of(host, server.getFirstMappedPort(),
            server.getDatabaseName(), server.getUsername(), server.getPassword(), mode);
        return DriverManager.getConnection(Storage.serverUrl(dialect, settings), server.getUsername(),
            server.getPassword());
    }

    private static boolean encrypted(Dialect dialect, Connection connection) throws SQLException {
        String query = dialect == Dialect.POSTGRES
            ? "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()"
            : "SHOW SESSION STATUS LIKE 'Ssl_version'";
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(query)) {
            assertThat(row.next()).isTrue();
            return dialect == Dialect.POSTGRES ? row.getBoolean(1) : !row.getString(2).isEmpty();
        }
    }

    /**
     * Writes {@code ca.pem}, {@code server.pem} (for {@code localhost} only), {@code server-key.pem} and
     * {@code trust.p12} (holding the authority) into {@link #keys}, with the JDK's keytool.
     */
    private static void createCertificates() throws Exception {
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Taffy Test CA",
            "-ext", "bc:c", "-validity", "2", "-keystore", "ca.p12", "-storetype", "PKCS12");
        keytool("-exportcert", "-alias", "ca", "-keystore", "ca.p12", "-rfc", "-file", "ca.pem");
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
            "-validity", "2", "-keystore", "server.p12", "-storetype", "PKCS12");
        keytool("-certreq", "-alias", "server", "-keystore", "server.p12", "-file", "server.csr");
        keytool("-gencert", "-alias", "ca", "-keystore", "ca.p12", "-infile", "server.csr", "-outfile", "server.pem",
            "-rfc", "-ext", "SAN=dns:localhost", "-validity", "2");

        KeyStore server = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keys.resolve("server.p12"))) {
            server.load(in, PASSWORD);
        }
        PrivateKey key = (PrivateKey) server.getKey("server", PASSWORD);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(keys.resolve("server-key.pem"), pem, StandardCharsets.US_ASCII);

        Certificate authority;
        try (InputStream in = Files.newInputStream(keys.resolve("ca.pem"))) {
            authority = CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, PASSWORD);
        trust.setCertificateEntry("taffy-test-ca", authority);
        try (var out = Files.newOutputStream(keys.resolve("trust.p12"))) {
            trust.store(out, PASSWORD);
        }
    }

    private static void keytool(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));
        command.addAll(List.of("-storepass", new String(PASSWORD)));
        Process process = new ProcessBuilder(command).directory(keys.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("keytool " + arguments[0] + " failed: " + output);
        }
    }
}
