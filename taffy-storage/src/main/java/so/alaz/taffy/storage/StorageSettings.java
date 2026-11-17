package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where storage keeps its data and how it connects: the database, the SQLite file or the server, the connection
 * pool, the number of database threads and how many SQLite backups to keep.
 *
 * <p>The table prefix, the database name and the host are concatenated into SQL and JDBC URLs, so the
 * constructors refuse values that could change the meaning of either.
 *
 * @param dialect the database
 * @param sqliteFile the SQLite database file, relative to the plugin's data folder; not blank for
 *     {@link Dialect#SQLITE}, ignored otherwise
 * @param server the server to connect to, or {@code null} for {@link Dialect#SQLITE}
 * @param pool the connection pool settings
 * @param asyncThreads the number of threads database work runs on, 1 or more. On SQLite these threads run the
 *     reads and complete the futures of writes, and writes run on one more thread of their own, one at a time in
 *     the order they were submitted. Work, or a stage chained onto its future, that waits on one of these threads
 *     for other database work needs another of them free, so with 1 such a wait never ends
 * @param backupKeep how many SQLite backups taken before a migration to keep, 0 or more
 */
public record StorageSettings(Dialect dialect, String sqliteFile, @Nullable Server server, Pool pool,
                              int asyncThreads, int backupKeep) {

    private static final Pattern PREFIX = Pattern.compile("([a-z_][a-z0-9_]{0,31})?");
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_$-]{1,64}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._-]+|\\[[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*]");
    private static final Duration MIN_CONNECTION_TIMEOUT = Duration.ofMillis(250);

    /**
     * Checks the settings.
     *
     * @throws NullPointerException if the dialect, the SQLite file or the pool is {@code null}
     * @throws IllegalArgumentException if the server is {@code null} for a dialect other than SQLite, the SQLite
     *     file is blank for SQLite, {@code asyncThreads} is below 1 or {@code backupKeep} is below 0
     */
    public StorageSettings {
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(sqliteFile, "sqliteFile");
        Objects.requireNonNull(pool, "pool");
        if (dialect == Dialect.SQLITE && sqliteFile.isBlank()) {
            throw new IllegalArgumentException("sqliteFile is blank; name the SQLite database file");
        }
        if (dialect != Dialect.SQLITE && server == null) {
            throw new IllegalArgumentException("server is missing; " + dialect.id() + " needs a server");
        }
        if (asyncThreads < 1) {
            throw new IllegalArgumentException("asyncThreads is " + asyncThreads + "; expected 1 or more");
        }
        if (backupKeep < 0) {
            throw new IllegalArgumentException("backupKeep is " + backupKeep + "; expected 0 or more");
        }
    }

    /**
     * Returns the text put before every table name: the server's table prefix, or an empty string for SQLite.
     *
     * @return the table prefix, possibly empty
     */
    public String tablePrefix() {
        return dialect == Dialect.SQLITE ? "" : Objects.requireNonNull(server).tablePrefix();
    }

    static String requirePrefix(String prefix) {
        Objects.requireNonNull(prefix, "tablePrefix");
        if (!PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("tablePrefix is \"" + prefix + "\"; use up to 32 lowercase letters,"
                + " digits and underscores, starting with a letter or underscore");
        }
        return prefix;
    }

    /**
     * A MySQL, MariaDB or PostgreSQL server. {@link #toString()} does not show the password.
     *
     * @param host a host name or IPv4 address of ASCII letters, digits, dots, hyphens and underscores, or an IPv6
     *     address in brackets, such as {@code [::1]}
     * @param port the port, 1 to 65535
     * @param database the database name: 1 to 64 letters, digits, underscores, dollar signs and hyphens
     * @param user the user name
     * @param password the password, empty for none
     * @param sslMode whether and how the connection is encrypted
     * @param tablePrefix the text put before every table name: empty, or up to 32 lowercase letters, digits and
     *     underscores starting with a letter or underscore
     */
    public record Server(String host, int port, String database, String user, String password, SslMode sslMode,
                         String tablePrefix) {

        /**
         * Checks the server settings.
         *
         * @throws NullPointerException if any component is {@code null}
         * @throws IllegalArgumentException if the host, the port, the database name or the table prefix is out
         *     of its documented range
         */
        public Server {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(database, "database");
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(password, "password");
            Objects.requireNonNull(sslMode, "sslMode");
            if (!HOST.matcher(host).matches()) {
                throw new IllegalArgumentException("host is \"" + host + "\"; expected a host name, an IPv4"
                    + " address or an IPv6 address in brackets such as [::1]");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port is " + port + "; expected 1 to 65535");
            }
            if (!DATABASE.matcher(database).matches()) {
                throw new IllegalArgumentException("database is \"" + database + "\"; use up to 64 letters,"
                    + " digits, underscores, dollar signs and hyphens");
            }
            requirePrefix(tablePrefix);
        }

        @Override
        public String toString() {
            return "Server[host=" + host + ", port=" + port + ", database=" + database + ", user=" + user
                + ", password=" + (password.isEmpty() ? "" : "(set)") + ", sslMode=" + sslMode
                + ", tablePrefix=" + tablePrefix + "]";
        }
    }

    /** Whether and how the connection to a server is encrypted. */
    public enum SslMode {
        /** The connection is not encrypted. */
        DISABLE,
        /** The connection is encrypted, without checking the server's certificate. */
        TRUST,
        /**
         * The connection is encrypted, and the server's certificate and host name are checked. The certificate is
         * checked against the JVM's default trust store on every server dialect.
         */
        VERIFY_FULL
    }

    /**
     * The connection pool.
     *
     * @param maxSize the most connections the pool opens, 1 or more
     * @param minIdle the idle connections the pool keeps open, 0 to {@code maxSize}
     * @param connectionTimeout how long a caller waits for a connection, 250 milliseconds or more
     */
    public record Pool(int maxSize, int minIdle, Duration connectionTimeout) {

        /**
         * Checks the pool settings.
         *
         * @throws NullPointerException if the connection timeout is {@code null}
         * @throws IllegalArgumentException if a value is out of its documented range
         */
        public Pool {
            Objects.requireNonNull(connectionTimeout, "connectionTimeout");
            if (maxSize < 1) {
                throw new IllegalArgumentException("maxSize is " + maxSize + "; expected 1 or more");
            }
            if (minIdle < 0 || minIdle > maxSize) {
                throw new IllegalArgumentException("minIdle is " + minIdle + "; expected 0 to " + maxSize);
            }
            if (connectionTimeout.compareTo(MIN_CONNECTION_TIMEOUT) < 0) {
                throw new IllegalArgumentException("connectionTimeout is " + connectionTimeout
                    + "; expected 250ms or more");
            }
        }
    }
}
