package so.alaz.taffy.storage;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Where storage keeps its data and how it connects: the database, the SQLite file or the server, the connection
 * pool, the number of database threads, how many SQLite backups to keep and the name of the version table.
 *
 * <p>The plugin starts from {@link #sqlite(String)} or {@link #server(Dialect, Server)} and replaces what its own
 * config sets, such as {@code StorageSettings.sqlite("data.db").asyncThreads(4).backupKeep(3)}; each method that
 * takes a value gives a copy and leaves this one unchanged. A factory gives a pool of {@code Pool.of(4)}, 2
 * database threads, 5 backups and the default version table.
 *
 * <p>The table prefix, the version table, the database name and the host are concatenated into SQL and JDBC URLs,
 * so the factories and methods refuse values that could change the meaning of either.
 *
 * <p>{@link #asyncThreads()} must not be above {@link Pool#maxSize()}: each database thread holds one connection
 * while it runs work, so a thread beyond the pool's size would wait for a connection another thread holds and fail
 * with a connection timeout under load. The two are set by separate methods, so {@link Storage#start} checks them
 * together and throws {@link IllegalArgumentException}. Connections above {@code asyncThreads} are never used.
 *
 * <p>Two settings are equal when every value is equal.
 */
public final class StorageSettings {

    private static final Pattern PREFIX = Pattern.compile("([a-z_][a-z0-9_]{0,31})?");
    private static final Pattern VERSION_TABLE = Pattern.compile("[a-z_][a-z0-9_]{0,53}");
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_$-]{1,64}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._-]+|\\[[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*]");
    private static final Duration MIN_CONNECTION_TIMEOUT = Duration.ofMillis(250);
    private static final Duration DEFAULT_SOCKET_TIMEOUT = Duration.ofSeconds(30);
    // The MySQL and MariaDB drivers hold the socket timeout as an int of milliseconds.
    private static final long MAX_SOCKET_TIMEOUT_SECONDS = Integer.MAX_VALUE / 1000;
    // Lowercase: the MySQL and MariaDB drivers read a property name in any letter case.
    private static final Set<String> RESERVED_PROPERTIES = Set.of("user", "password", "sslmode", "ssl", "usessl",
        "requiressl", "verifyservercertificate", "trustservercertificate", "disablesslhostnameverification",
        "sslfactory", "allowpublickeyretrieval", "useaffectedrows", "sockettimeout");
    // With either, the MySQL driver replaces a dropped connection without an error, and the new session holds no
    // migration lock.
    private static final Set<String> RECONNECT_PROPERTIES = Set.of("autoreconnect", "autoreconnectforpools");

    static final String LEGACY_VERSION_TABLE = "schema_version";
    static final int MAX_VERSION_TABLE_LENGTH = 54;

    private static final int DEFAULT_ASYNC_THREADS = 2;
    private static final int DEFAULT_BACKUP_KEEP = 5;

    private final Dialect dialect;
    private final String sqliteFile;
    private final @Nullable Server server;
    private final Pool pool;
    private final int asyncThreads;
    private final int backupKeep;
    private final @Nullable String versionTable;

    private StorageSettings(Dialect dialect, String sqliteFile, @Nullable Server server, Pool pool, int asyncThreads,
                            int backupKeep, @Nullable String versionTable) {
        Objects.requireNonNull(pool, "pool");
        if (asyncThreads < 1) {
            throw new IllegalArgumentException("asyncThreads is " + asyncThreads + "; expected 1 or more");
        }
        if (backupKeep < 0) {
            throw new IllegalArgumentException("backupKeep is " + backupKeep + "; expected 0 or more");
        }
        this.dialect = dialect;
        this.sqliteFile = sqliteFile;
        this.server = server;
        this.pool = pool;
        this.asyncThreads = asyncThreads;
        this.backupKeep = backupKeep;
        this.versionTable = versionTable;
    }

    /**
     * Gives the settings of a SQLite database, with a pool of {@code Pool.of(4)}, 2 database threads, 5 backups and
     * the default version table.
     *
     * @param sqliteFile the SQLite database file, relative to the plugin's data folder; not blank
     * @return the settings
     * @throws NullPointerException if the file is {@code null}
     * @throws IllegalArgumentException if the file is blank
     */
    public static StorageSettings sqlite(String sqliteFile) {
        Objects.requireNonNull(sqliteFile, "sqliteFile");
        if (sqliteFile.isBlank()) {
            throw new IllegalArgumentException("sqliteFile is blank; name the SQLite database file");
        }
        return new StorageSettings(Dialect.SQLITE, sqliteFile, null, Pool.of(4), DEFAULT_ASYNC_THREADS,
            DEFAULT_BACKUP_KEEP, null);
    }

    /**
     * Gives the settings of a database on a server, with a pool of {@code Pool.of(4)}, 2 database threads and the
     * default version table.
     *
     * @param dialect the database: {@link Dialect#MYSQL}, {@link Dialect#MARIADB} or {@link Dialect#POSTGRES}
     * @param server the server to connect to
     * @return the settings
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the dialect is {@link Dialect#SQLITE}, which {@link #sqlite(String)}
     *     builds
     */
    public static StorageSettings server(Dialect dialect, Server server) {
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(server, "server");
        if (dialect == Dialect.SQLITE) {
            throw new IllegalArgumentException("sqlite has no server; build its settings with"
                + " StorageSettings.sqlite");
        }
        return new StorageSettings(dialect, "", server, Pool.of(4), DEFAULT_ASYNC_THREADS, DEFAULT_BACKUP_KEEP,
            null);
    }

    /**
     * Gives the database.
     *
     * @return the dialect
     */
    public Dialect dialect() {
        return dialect;
    }

    /**
     * Gives the SQLite database file, relative to the plugin's data folder.
     *
     * @return the file, not blank for {@link Dialect#SQLITE} and empty for a server
     */
    public String sqliteFile() {
        return sqliteFile;
    }

    /**
     * Gives the server to connect to.
     *
     * @return the server, or {@code null} for {@link Dialect#SQLITE}
     */
    public @Nullable Server server() {
        return server;
    }

    /**
     * Gives the connection pool settings.
     *
     * @return the pool
     */
    public Pool pool() {
        return pool;
    }

    /**
     * Gives the number of threads database work runs on. On SQLite these threads run the reads and complete the
     * futures of writes, and writes run on one more thread of their own, one at a time in the order they were
     * submitted. Work, or a stage chained onto its future, that waits on one of these threads for other database
     * work needs another of them free, so with 1 such a wait never ends.
     *
     * @return the number of database threads, 1 or more
     */
    public int asyncThreads() {
        return asyncThreads;
    }

    /**
     * Gives how many SQLite backups taken before a migration to keep.
     *
     * @return the number of backups, 0 or more; ignored for a server
     */
    public int backupKeep() {
        return backupKeep;
    }

    /**
     * Gives the name the plugin set for the table that records the applied migrations.
     *
     * @return the full name of the version table, or {@code null} for the default name, which
     *     {@link #versionTable(String)} describes
     */
    public @Nullable String versionTable() {
        return versionTable;
    }

    /**
     * Returns the text put before every table name: the server's table prefix, or an empty string for SQLite.
     *
     * @return the table prefix, possibly empty
     */
    public String tablePrefix() {
        return server == null ? "" : server.tablePrefix();
    }

    /**
     * Gives these settings with another connection pool.
     *
     * @param pool the connection pool settings
     * @return a copy of these settings with {@code pool} replaced
     * @throws NullPointerException if {@code pool} is {@code null}
     */
    public StorageSettings pool(Pool pool) {
        return new StorageSettings(dialect, sqliteFile, server, pool, asyncThreads, backupKeep, versionTable);
    }

    /**
     * Gives these settings with another number of database threads. {@link Storage#start} refuses a number above
     * {@link Pool#maxSize()}.
     *
     * @param asyncThreads the number of threads database work runs on, 1 or more
     * @return a copy of these settings with {@code asyncThreads} replaced
     * @throws IllegalArgumentException if {@code asyncThreads} is below 1
     */
    public StorageSettings asyncThreads(int asyncThreads) {
        return new StorageSettings(dialect, sqliteFile, server, pool, asyncThreads, backupKeep, versionTable);
    }

    /**
     * Gives these settings with another number of SQLite backups to keep.
     *
     * @param backupKeep how many SQLite backups taken before a migration to keep, 0 or more; 0 takes none
     * @return a copy of these settings with {@code backupKeep} replaced
     * @throws IllegalArgumentException if {@code backupKeep} is below 0
     */
    public StorageSettings backupKeep(int backupKeep) {
        return new StorageSettings(dialect, sqliteFile, server, pool, asyncThreads, backupKeep, versionTable);
    }

    /**
     * Gives these settings with a name for the version table, the table that records the applied migrations. The
     * name is used as it is, on every dialect, with no table prefix added, so a plugin that lets the owner set a
     * table prefix and names this table puts the prefix into the name itself: two servers that share a database
     * under different prefixes otherwise share one migration history.
     *
     * <p>Without a name the table is {@code schema_version} on SQLite, whose file holds the tables of one plugin,
     * and {@code <prefix><plugin>_schema_version} on MySQL, MariaDB and PostgreSQL, where {@code <plugin>} is the
     * plugin's name in lowercase with every character other than a lowercase letter, a digit or an underscore
     * replaced by an underscore, cut at its end where the whole name would pass 54 characters, and with an
     * underscore before it where the name would start with a digit. Two plugins that share a database and a prefix
     * so keep a version table each. Two plugin names that differ only in characters that are replaced, such as
     * {@code My-Plugin} and {@code My_Plugin}, or only after the cut, give the same default name, so one of the two
     * plugins sets a name.
     *
     * <p>The name must stay the same for every release that starts on a database: storage finds its history only
     * under this name, so under another name it runs every migration again. The default name follows the plugin's
     * name, so a plugin that is renamed sets the name it had before. When the earlier table of 0.1.0 to 0.3.2 was
     * already copied into a version table that has recorded more since, a start under another name stops with a
     * {@link StorageException} that names that table.
     *
     * <p>A {@code <prefix>schema_version} that holds none of this plugin's checksums, with a row that carries the
     * version and the name of one of this plugin's migrations, stops the start with a {@link StorageException}
     * before anything changes, unless another version table under the prefix already holds every row of it.
     *
     * @param versionTable the full name of the version table: 1 to 54 lowercase letters, digits and underscores,
     *     starting with a letter or underscore. It is written into SQL unquoted, so it must not be a word the
     *     database reserves
     * @return a copy of these settings with {@code versionTable} replaced
     * @throws NullPointerException if {@code versionTable} is {@code null}
     * @throws IllegalArgumentException if the name is out of its range
     */
    public StorageSettings versionTable(String versionTable) {
        Objects.requireNonNull(versionTable, "versionTable");
        if (!VERSION_TABLE.matcher(versionTable).matches()) {
            throw new IllegalArgumentException("versionTable is \"" + versionTable + "\"; use 1 to "
                + MAX_VERSION_TABLE_LENGTH + " lowercase letters, digits and underscores, starting with a letter or"
                + " underscore");
        }
        return new StorageSettings(dialect, sqliteFile, server, pool, asyncThreads, backupKeep, versionTable);
    }

    /** The name of the version table for the plugin named {@code plugin}: the one set, or the default. */
    String versionTableFor(String plugin) {
        if (versionTable != null) {
            return versionTable;
        }
        return dialect == Dialect.SQLITE ? LEGACY_VERSION_TABLE : defaultVersionTable(tablePrefix(), plugin);
    }

    static String defaultVersionTable(String prefix, String plugin) {
        String slug = plugin.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (prefix.isEmpty() && slug.charAt(0) >= '0' && slug.charAt(0) <= '9') {
            slug = "_" + slug;
        }
        String suffix = "_" + LEGACY_VERSION_TABLE;
        int room = MAX_VERSION_TABLE_LENGTH - prefix.length() - suffix.length();
        return prefix + (slug.length() > room ? slug.substring(0, room) : slug) + suffix;
    }

    static String requirePrefix(String prefix) {
        Objects.requireNonNull(prefix, "tablePrefix");
        if (!PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("tablePrefix is \"" + prefix + "\"; use up to 32 lowercase letters,"
                + " digits and underscores, starting with a letter or underscore");
        }
        return prefix;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StorageSettings that && dialect == that.dialect && sqliteFile.equals(that.sqliteFile)
            && Objects.equals(server, that.server) && pool.equals(that.pool) && asyncThreads == that.asyncThreads
            && backupKeep == that.backupKeep && Objects.equals(versionTable, that.versionTable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dialect, sqliteFile, server, pool, asyncThreads, backupKeep, versionTable);
    }

    @Override
    public String toString() {
        return "StorageSettings[dialect=" + dialect + ", sqliteFile=" + sqliteFile + ", server=" + server
            + ", pool=" + pool + ", asyncThreads=" + asyncThreads + ", backupKeep=" + backupKeep
            + ", versionTable=" + versionTable + "]";
    }

    /**
     * A MySQL, MariaDB or PostgreSQL server. It is built through {@link #of}, which gives a server with no table
     * prefix, a socket timeout of 30 seconds and no driver properties, and {@link #tablePrefix(String)},
     * {@link #socketTimeout(Duration)} and {@link #properties(Map)} each give a copy with that value replaced.
     * {@link #toString()} shows neither the password nor the value of a driver property. Two servers are equal
     * when every value is equal.
     */
    public static final class Server {

        private final String host;
        private final int port;
        private final String database;
        private final String user;
        private final String password;
        private final SslMode sslMode;
        private final String tablePrefix;
        private final Duration socketTimeout;
        private final Map<String, String> properties;

        private Server(String host, int port, String database, String user, String password, SslMode sslMode,
                       String tablePrefix, Duration socketTimeout, Map<String, String> properties) {
            this.host = host;
            this.port = port;
            this.database = database;
            this.user = user;
            this.password = password;
            this.sslMode = sslMode;
            this.tablePrefix = tablePrefix;
            this.socketTimeout = socketTimeout;
            this.properties = properties;
        }

        /**
         * Gives a server with no table prefix, a socket timeout of 30 seconds and no driver properties.
         *
         * @param host a host name or IPv4 address of ASCII letters, digits, dots, hyphens and underscores, or an
         *     IPv6 address in brackets, such as {@code [::1]}
         * @param port the port, 1 to 65535
         * @param database the database name: 1 to 64 letters, digits, underscores, dollar signs and hyphens
         * @param user the user name
         * @param password the password, empty for none
         * @param sslMode whether and how the connection is encrypted
         * @return the server
         * @throws NullPointerException if an argument is {@code null}
         * @throws IllegalArgumentException if the host, the port or the database name is out of its range
         */
        public static Server of(String host, int port, String database, String user, String password,
                                SslMode sslMode) {
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
            return new Server(host, port, database, user, password, sslMode, "", DEFAULT_SOCKET_TIMEOUT, Map.of());
        }

        /**
         * Gives the host.
         *
         * @return a host name, an IPv4 address or an IPv6 address in brackets
         */
        public String host() {
            return host;
        }

        /**
         * Gives the port.
         *
         * @return the port, 1 to 65535
         */
        public int port() {
            return port;
        }

        /**
         * Gives the database name.
         *
         * @return the database name
         */
        public String database() {
            return database;
        }

        /**
         * Gives the user name.
         *
         * @return the user name
         */
        public String user() {
            return user;
        }

        /**
         * Gives the password.
         *
         * @return the password, empty for none
         */
        public String password() {
            return password;
        }

        /**
         * Gives whether and how the connection is encrypted.
         *
         * @return the SSL mode
         */
        public SslMode sslMode() {
            return sslMode;
        }

        /**
         * Gives the text put before every table name.
         *
         * @return the table prefix, possibly empty
         */
        public String tablePrefix() {
            return tablePrefix;
        }

        /**
         * Gives how long a connection waits for the server to answer during normal work.
         *
         * @return the socket timeout, a whole number of seconds from 1 to 2147483
         */
        public Duration socketTimeout() {
            return socketTimeout;
        }

        /**
         * Gives the properties handed to the JDBC driver with every connection.
         *
         * @return the driver properties by name, in the order they were given; unmodifiable and possibly empty
         */
        public Map<String, String> properties() {
            return properties;
        }

        /**
         * Gives this server with another table prefix.
         *
         * @param tablePrefix the text put before every table name: empty, or up to 32 lowercase letters, digits
         *     and underscores starting with a letter or underscore
         * @return a copy of this server with {@code tablePrefix} replaced
         * @throws NullPointerException if {@code tablePrefix} is {@code null}
         * @throws IllegalArgumentException if the prefix is out of its range
         */
        public Server tablePrefix(String tablePrefix) {
            return new Server(host, port, database, user, password, sslMode, requirePrefix(tablePrefix),
                socketTimeout, properties);
        }

        /**
         * Gives this server with another socket timeout: how long a connection waits for the server to answer
         * during normal work. A statement that waits longer, a wait for a row lock included, fails with an
         * {@link java.sql.SQLException} and its connection is closed; whether the server still ran the statement is
         * then unknown, and {@link Storage#asyncRetrying} does not run the work again. Migrations run without this
         * timeout. A plugin whose statements or lock waits may pass 30 seconds sets a longer one.
         *
         * @param socketTimeout the timeout, a whole number of seconds from 1 to 2147483, the longest the drivers
         *     hold
         * @return a copy of this server with {@code socketTimeout} replaced
         * @throws NullPointerException if {@code socketTimeout} is {@code null}
         * @throws IllegalArgumentException if the timeout is out of its range or holds a part of a second
         */
        public Server socketTimeout(Duration socketTimeout) {
            Objects.requireNonNull(socketTimeout, "socketTimeout");
            if (socketTimeout.getNano() != 0 || socketTimeout.getSeconds() < 1
                || socketTimeout.getSeconds() > MAX_SOCKET_TIMEOUT_SECONDS) {
                throw new IllegalArgumentException("socketTimeout is " + socketTimeout + "; expected a whole number"
                    + " of seconds from 1 to " + MAX_SOCKET_TIMEOUT_SECONDS);
            }
            return new Server(host, port, database, user, password, sslMode, tablePrefix, socketTimeout, properties);
        }

        /**
         * Gives this server with other driver properties, which replace the ones it had. Each is handed to the JDBC
         * driver with every connection, under the name and with the value the driver documents, such as
         * {@code connectTimeout} or {@code sessionVariables} on MySQL and MariaDB and {@code ApplicationName} on
         * PostgreSQL. Storage does not check a name or a value the driver does not know.
         *
         * <p>A property must not name what storage sets itself, in any letter case, because a driver would let it
         * replace that value: {@code user} and {@code password}; the SSL mode and the switches that replace it
         * ({@code sslMode}, {@code ssl}, {@code useSSL}, {@code requireSSL}, {@code verifyServerCertificate},
         * {@code trustServerCertificate}, {@code disableSslHostnameVerification}, {@code sslfactory}); and
         * {@code allowPublicKeyRetrieval}, {@code useAffectedRows} and {@code socketTimeout}. These are set through
         * {@link #of} and {@link #socketTimeout(Duration)}. {@code autoReconnect} and
         * {@code autoReconnectForPools} are refused as well, in any letter case: with either, the MySQL driver
         * replaces a dropped connection without an error, and the new session no longer holds the migration lock.
         *
         * @param properties the driver properties by name; copied, so a later change to the map changes nothing
         * @return a copy of this server with {@code properties} replaced
         * @throws NullPointerException if {@code properties}, a name or a value is {@code null}
         * @throws IllegalArgumentException if a name is blank, names what storage sets itself or is one of the two
         *     reconnect properties
         */
        public Server properties(Map<String, String> properties) {
            Objects.requireNonNull(properties, "properties");
            Map<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> property : properties.entrySet()) {
                String name = Objects.requireNonNull(property.getKey(), "property name");
                if (name.isBlank()) {
                    throw new IllegalArgumentException("a driver property has a blank name");
                }
                if (RESERVED_PROPERTIES.contains(name.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("the driver property \"" + name + "\" would replace a value"
                        + " storage sets itself; set the user, the password, the SSL mode and the socket timeout"
                        + " through the server settings");
                }
                if (RECONNECT_PROPERTIES.contains(name.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("the driver property \"" + name + "\" would let the driver"
                        + " replace a dropped connection without an error, and the new session would not hold the"
                        + " migration lock; storage does not support it");
                }
                copy.put(name, Objects.requireNonNull(property.getValue(), "value of driver property " + name));
            }
            return new Server(host, port, database, user, password, sslMode, tablePrefix, socketTimeout,
                Collections.unmodifiableMap(copy));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Server that && host.equals(that.host) && port == that.port
                && database.equals(that.database) && user.equals(that.user) && password.equals(that.password)
                && sslMode == that.sslMode && tablePrefix.equals(that.tablePrefix)
                && socketTimeout.equals(that.socketTimeout) && properties.equals(that.properties);
        }

        @Override
        public int hashCode() {
            return Objects.hash(host, port, database, user, password, sslMode, tablePrefix, socketTimeout,
                properties);
        }

        @Override
        public String toString() {
            return "Server[host=" + host + ", port=" + port + ", database=" + database + ", user=" + user
                + ", password=" + (password.isEmpty() ? "" : "(set)") + ", sslMode=" + sslMode
                + ", tablePrefix=" + tablePrefix + ", socketTimeout=" + socketTimeout + ", properties="
                + properties.keySet() + "]";
        }
    }

    /**
     * Whether and how the connection to a server is encrypted. Constants may be added in a later release, so a
     * {@code switch} over this enum needs a {@code default} branch.
     */
    public enum SslMode {
        /** The connection is not encrypted. */
        DISABLE,
        /**
         * The connection is encrypted when the server offers TLS and is not encrypted when it does not, and the
         * server's certificate is never checked: {@code sslMode=PREFERRED} on MySQL and {@code sslmode=prefer} on
         * PostgreSQL, which is what those drivers do for a URL that names no mode. Someone who can change the
         * traffic between the two machines can make the connection fall back to no encryption, so this mode
         * protects only against reading the traffic.
         *
         * <p>The MariaDB driver has no such mode. On MariaDB this mode is {@link #DISABLE}, which is what that
         * driver does for a URL that names no mode: the connection is not encrypted, whether or not the server
         * offers TLS. {@link #TRUST} or {@link #VERIFY_FULL} encrypts a MariaDB connection.
         */
        PREFER,
        /** The connection is encrypted, without checking the server's certificate. */
        TRUST,
        /**
         * The connection is encrypted, and the server's certificate and host name are checked. The certificate is
         * checked against the JVM's default trust store on every server dialect.
         */
        VERIFY_FULL
    }

    /**
     * The connection pool. It is built through {@link #of}, which gives a pool that keeps 1 idle connection open
     * and lets a caller wait 10 seconds for a connection, and a method named after each value gives a copy with
     * that value replaced. Two pools are equal when every value is equal.
     */
    public static final class Pool {

        private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(10);

        private final int maxSize;
        private final int minIdle;
        private final Duration connectionTimeout;

        private Pool(int maxSize, int minIdle, Duration connectionTimeout) {
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
            this.maxSize = maxSize;
            this.minIdle = minIdle;
            this.connectionTimeout = connectionTimeout;
        }

        /**
         * Gives a pool of up to {@code maxSize} connections that keeps 1 idle connection open and lets a caller
         * wait 10 seconds for a connection.
         *
         * @param maxSize the most connections the pool opens, 1 or more, and not below
         *     {@link StorageSettings#asyncThreads()}
         * @return the pool
         * @throws IllegalArgumentException if {@code maxSize} is below 1
         */
        public static Pool of(int maxSize) {
            return new Pool(maxSize, 1, DEFAULT_CONNECTION_TIMEOUT);
        }

        /**
         * Gives the most connections the pool opens. Each database thread uses one connection at a time, so
         * connections above {@link StorageSettings#asyncThreads()} are never used.
         *
         * @return the size, 1 or more
         */
        public int maxSize() {
            return maxSize;
        }

        /**
         * Gives the idle connections the pool keeps open.
         *
         * @return the number of idle connections, 0 to {@link #maxSize()}
         */
        public int minIdle() {
            return minIdle;
        }

        /**
         * Gives how long a caller waits for a connection.
         *
         * @return the timeout, 250 milliseconds or more
         */
        public Duration connectionTimeout() {
            return connectionTimeout;
        }

        /**
         * Gives this pool with another size.
         *
         * @param maxSize the most connections the pool opens, 1 or more and not below {@link #minIdle()}
         * @return a copy of this pool with {@code maxSize} replaced
         * @throws IllegalArgumentException if {@code maxSize} is below 1 or below {@link #minIdle()}
         */
        public Pool maxSize(int maxSize) {
            return new Pool(maxSize, minIdle, connectionTimeout);
        }

        /**
         * Gives this pool with another number of idle connections.
         *
         * @param minIdle the idle connections the pool keeps open, 0 to {@link #maxSize()}
         * @return a copy of this pool with {@code minIdle} replaced
         * @throws IllegalArgumentException if {@code minIdle} is out of its range
         */
        public Pool minIdle(int minIdle) {
            return new Pool(maxSize, minIdle, connectionTimeout);
        }

        /**
         * Gives this pool with another connection timeout.
         *
         * @param connectionTimeout how long a caller waits for a connection, 250 milliseconds or more
         * @return a copy of this pool with {@code connectionTimeout} replaced
         * @throws NullPointerException if {@code connectionTimeout} is {@code null}
         * @throws IllegalArgumentException if {@code connectionTimeout} is below 250 milliseconds
         */
        public Pool connectionTimeout(Duration connectionTimeout) {
            return new Pool(maxSize, minIdle, connectionTimeout);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Pool that && maxSize == that.maxSize && minIdle == that.minIdle
                && connectionTimeout.equals(that.connectionTimeout);
        }

        @Override
        public int hashCode() {
            return Objects.hash(maxSize, minIdle, connectionTimeout);
        }

        @Override
        public String toString() {
            return "Pool[maxSize=" + maxSize + ", minIdle=" + minIdle + ", connectionTimeout=" + connectionTimeout
                + "]";
        }
    }
}
