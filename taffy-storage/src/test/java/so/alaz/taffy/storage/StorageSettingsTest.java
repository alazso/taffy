package so.alaz.taffy.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class StorageSettingsTest {

    private static final StorageSettings.Pool POOL = StorageSettings.Pool.of(8).minIdle(2);

    private static StorageSettings.Server server(String host, int port, String database, String password,
                                                 String prefix) {
        return StorageSettings.Server.of(host, port, database, "shop", password,
            StorageSettings.SslMode.VERIFY_FULL).tablePrefix(prefix);
    }

    private static StorageSettings.Server server(String prefix) {
        return server("db.example", 3307, "app", "0451", prefix);
    }

    private static StorageSettings mariaDb(String prefix) {
        return StorageSettings.server(Dialect.MARIADB, server(prefix)).pool(POOL);
    }

    @Test
    void settingsWithTheSameValuesAreEqual() {
        StorageSettings first = mariaDb("p_");
        StorageSettings second = mariaDb("p_");

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(mariaDb("q_"))
            .isNotEqualTo(first.asyncThreads(3))
            .isNotEqualTo(first.backupKeep(1))
            .isNotEqualTo(first.pool(POOL.maxSize(9)))
            .isNotEqualTo(first.versionTable("shop_versions"));
        assertThat(first.versionTable("shop_versions")).isEqualTo(second.versionTable("shop_versions"));
        assertThat(first.server()).isEqualTo(server("p_"));
        assertThat(first.pool()).isEqualTo(StorageSettings.Pool.of(8).minIdle(2));
    }

    @Test
    void aFactoryGivesAPoolOfFourTwoThreadsFiveBackupsAndNoVersionTableName() {
        StorageSettings sqlite = StorageSettings.sqlite("data.db");
        StorageSettings mariaDb = StorageSettings.server(Dialect.MARIADB, server("p_"));

        for (StorageSettings settings : new StorageSettings[] {sqlite, mariaDb}) {
            assertThat(settings.pool()).isEqualTo(StorageSettings.Pool.of(4));
            assertThat(settings.asyncThreads()).isEqualTo(2);
            assertThat(settings.backupKeep()).isEqualTo(5);
            assertThat(settings.versionTable()).isNull();
        }
        assertThat(StorageSettings.Pool.of(4).minIdle()).isEqualTo(1);
        assertThat(StorageSettings.Pool.of(4).connectionTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(mariaDb.dialect()).isEqualTo(Dialect.MARIADB);
        assertThat(mariaDb.sqliteFile()).isEmpty();
    }

    @Test
    void eachMethodThatTakesAValueGivesACopyAndLeavesTheOriginal() {
        StorageSettings settings = StorageSettings.sqlite("data.db");

        StorageSettings changed = settings.pool(POOL).asyncThreads(6).backupKeep(0).versionTable("shop_versions");

        assertThat(changed.pool()).isEqualTo(POOL);
        assertThat(changed.asyncThreads()).isEqualTo(6);
        assertThat(changed.backupKeep()).isZero();
        assertThat(changed.versionTable()).isEqualTo("shop_versions");
        assertThat(changed.sqliteFile()).isEqualTo("data.db");
        assertThat(settings).isEqualTo(StorageSettings.sqlite("data.db"));
        assertThat(POOL.maxSize(3).minIdle(3).connectionTimeout(Duration.ofSeconds(1)))
            .isEqualTo(StorageSettings.Pool.of(3).minIdle(3).connectionTimeout(Duration.ofSeconds(1)));
        assertThat(POOL).isEqualTo(StorageSettings.Pool.of(8).minIdle(2));
        assertThat(server("p_").tablePrefix("q_")).isEqualTo(server("q_")).isNotEqualTo(server("p_"));
    }

    @Test
    void aServerFromTheFactoryWaitsThirtySecondsOnItsSocketAndHasNoDriverProperties() {
        StorageSettings.Server server = StorageSettings.Server.of("db.example", 3306, "app", "shop", "",
            StorageSettings.SslMode.PREFER);

        assertThat(server.socketTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(server.properties()).isEmpty();
        assertThat(server.tablePrefix()).isEmpty();
        assertThat(server.sslMode()).isEqualTo(StorageSettings.SslMode.PREFER);
    }

    @Test
    void theSocketTimeoutAndTheDriverPropertiesAreReplacedInACopy() {
        StorageSettings.Server server = server("p_");
        Map<String, String> given = new LinkedHashMap<>();
        given.put("sessionVariables", "wait_timeout=600");
        given.put("connectTimeout", "5000");

        StorageSettings.Server changed = server.socketTimeout(Duration.ofMinutes(2)).properties(given);
        given.put("tcpKeepAlive", "true");

        assertThat(changed.socketTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(changed.properties()).containsExactly(Map.entry("sessionVariables", "wait_timeout=600"),
            Map.entry("connectTimeout", "5000"));
        assertThat(changed.tablePrefix("q_").socketTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(changed.tablePrefix("q_").properties()).isEqualTo(changed.properties());
        assertThat(changed.properties(Map.of()).properties()).isEmpty();
        assertThatThrownBy(() -> changed.properties().put("tcpKeepAlive", "true"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(server).isEqualTo(server("p_")).isNotEqualTo(server.socketTimeout(Duration.ofSeconds(31)))
            .isNotEqualTo(server.properties(Map.of("connectTimeout", "5000")));
        assertThat(server.properties(Map.of("connectTimeout", "5000")))
            .isEqualTo(server.properties(Map.of("connectTimeout", "5000")))
            .hasSameHashCodeAs(server.properties(Map.of("connectTimeout", "5000")));
        assertThat(server.socketTimeout(Duration.ofSeconds(1)).socketTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(server.socketTimeout(Duration.ofSeconds(2_147_483)).socketTimeout())
            .isEqualTo(Duration.ofSeconds(2_147_483));
    }

    @Test
    void aSocketTimeoutOutsideWholeSecondsFromOneIsRefused() {
        StorageSettings.Server server = server("p_");

        assertThatThrownBy(() -> server.socketTimeout(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("socketTimeout is PT0S; expected a whole number of seconds from 1 to 2147483");
        assertThatThrownBy(() -> server.socketTimeout(Duration.ofSeconds(-5)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("socketTimeout is PT-5S; expected a whole number of seconds from 1 to 2147483");
        assertThatThrownBy(() -> server.socketTimeout(Duration.ofMillis(1500)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("socketTimeout is PT1.5S; expected a whole number of seconds from 1 to 2147483");
        assertThatThrownBy(() -> server.socketTimeout(Duration.ofSeconds(2_147_484)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("socketTimeout is PT596H31M24S; expected a whole number of seconds from 1 to 2147483");
        assertThatThrownBy(() -> server.socketTimeout(null))
            .isInstanceOf(NullPointerException.class).hasMessage("socketTimeout");
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "password", "PASSWORD", "sslMode", "sslmode", "SSLMODE", "ssl", "useSSL",
        "useSsl", "requireSSL", "verifyServerCertificate", "trustServerCertificate",
        "disableSslHostnameVerification", "sslfactory", "allowPublicKeyRetrieval", "useAffectedRows",
        "socketTimeout", "SocketTimeout"})
    void aDriverPropertyThatWouldReplaceAValueStorageSetsIsRefusedInAnyLetterCase(String name) {
        assertThatThrownBy(() -> server("p_").properties(Map.of("connectTimeout", "5000", name, "x")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the driver property \"" + name + "\" would replace a value storage sets itself; set the"
                + " user, the password, the SSL mode and the socket timeout through the server settings");
    }

    @ParameterizedTest
    @ValueSource(strings = {"autoReconnect", "AUTORECONNECT", "autoReconnectForPools"})
    void aDriverPropertyThatReconnectsADroppedConnectionIsRefusedInAnyLetterCase(String name) {
        assertThatThrownBy(() -> server("p_").properties(Map.of(name, "true")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the driver property \"" + name + "\" would let the driver replace a dropped connection"
                + " without an error, and the new session would not hold the migration lock; storage does not"
                + " support it");
    }

    @Test
    void aDriverPropertyWithoutANameOrAValueIsRefused() {
        StorageSettings.Server server = server("p_");
        Map<String, String> noValue = new HashMap<>();
        noValue.put("connectTimeout", null);
        Map<String, String> noName = new HashMap<>();
        noName.put(null, "5000");

        assertThatThrownBy(() -> server.properties(Map.of(" ", "5000")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("a driver property has a blank name");
        assertThatThrownBy(() -> server.properties(noValue))
            .isInstanceOf(NullPointerException.class).hasMessage("value of driver property connectTimeout");
        assertThatThrownBy(() -> server.properties(noName))
            .isInstanceOf(NullPointerException.class).hasMessage("property name");
        assertThatThrownBy(() -> server.properties(null))
            .isInstanceOf(NullPointerException.class).hasMessage("properties");
    }

    @Test
    void theServerToStringHidesASetPasswordAndShowsAnEmptyOne() {
        assertThat(server("p_")).hasToString("Server[host=db.example, port=3307, database=app, user=shop,"
            + " password=(set), sslMode=VERIFY_FULL, tablePrefix=p_, socketTimeout=PT30S, properties=[]]");
        assertThat(server("db.example", 3307, "app", "", "p_")).hasToString("Server[host=db.example,"
            + " port=3307, database=app, user=shop, password=, sslMode=VERIFY_FULL, tablePrefix=p_,"
            + " socketTimeout=PT30S, properties=[]]");
        assertThat(server("p_").socketTimeout(Duration.ofSeconds(90))
            .properties(Map.of("trustStorePassword", "hunter2")).toString())
            .endsWith("tablePrefix=p_, socketTimeout=PT1M30S, properties=[trustStorePassword]]")
            .doesNotContain("hunter2");
        assertThat(StorageSettings.server(Dialect.MYSQL, server("p_")).toString())
            .doesNotContain("0451").contains("password=(set)");
        assertThat(StorageSettings.sqlite("data.db")).hasToString("StorageSettings[dialect=SQLITE,"
            + " sqliteFile=data.db, server=null, pool=Pool[maxSize=4, minIdle=1, connectionTimeout=PT10S],"
            + " asyncThreads=2, backupKeep=5, versionTable=null]");
    }

    @Test
    void sqliteNeedsNoServerAndHasNoTablePrefix() {
        StorageSettings sqlite = StorageSettings.sqlite("data.db");

        assertThat(sqlite.dialect()).isEqualTo(Dialect.SQLITE);
        assertThat(sqlite.server()).isNull();
        assertThat(sqlite.sqliteFile()).isEqualTo("data.db");
        assertThat(sqlite.tablePrefix()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = "SQLITE", mode = EnumSource.Mode.EXCLUDE)
    void aServerDialectUsesTheServerPrefix(Dialect dialect) {
        assertThat(StorageSettings.server(dialect, server("p_")).tablePrefix()).isEqualTo("p_");
        assertThat(StorageSettings.server(dialect, server("")).tablePrefix()).isEmpty();
        assertThat(StorageSettings.server(dialect, StorageSettings.Server.of("db.example", 3306, "app", "shop", "",
            StorageSettings.SslMode.DISABLE)).tablePrefix()).isEmpty();
    }

    @Test
    void theServerFactoryRefusesSqlite() {
        assertThatThrownBy(() -> StorageSettings.server(Dialect.SQLITE, server("p_")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("sqlite has no server; build its settings with StorageSettings.sqlite");
    }

    @Test
    void sqliteRefusesABlankFile() {
        assertThatThrownBy(() -> StorageSettings.sqlite(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("sqliteFile is blank; name the SQLite database file");
    }

    @Test
    void threadAndBackupCountsOutOfRangeAreRefused() {
        StorageSettings settings = StorageSettings.sqlite("data.db");

        assertThatThrownBy(() -> settings.asyncThreads(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("asyncThreads is 0; expected 1 or more");
        assertThatThrownBy(() -> settings.backupKeep(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("backupKeep is -1; expected 0 or more");
    }

    @Test
    void moreThreadsThanThePoolHoldsAreAcceptedUntilStorageStarts() {
        StorageSettings settings = StorageSettings.sqlite("data.db");

        assertThat(settings.asyncThreads(9).pool(StorageSettings.Pool.of(9)).asyncThreads()).isEqualTo(9);
        assertThat(settings.pool(StorageSettings.Pool.of(1)).asyncThreads(1).pool().maxSize()).isEqualTo(1);
    }

    @Test
    void nullReferencesAreRefusedByName() {
        assertThatThrownBy(() -> StorageSettings.server(null, server("p_")))
            .isInstanceOf(NullPointerException.class).hasMessage("dialect");
        assertThatThrownBy(() -> StorageSettings.server(Dialect.MYSQL, null))
            .isInstanceOf(NullPointerException.class).hasMessage("server");
        assertThatThrownBy(() -> StorageSettings.sqlite(null))
            .isInstanceOf(NullPointerException.class).hasMessage("sqliteFile");
        assertThatThrownBy(() -> StorageSettings.sqlite("data.db").pool(null))
            .isInstanceOf(NullPointerException.class).hasMessage("pool");
        assertThatThrownBy(() -> StorageSettings.sqlite("data.db").versionTable(null))
            .isInstanceOf(NullPointerException.class).hasMessage("versionTable");
        assertThatThrownBy(() -> server(null, 3306, "app", "", ""))
            .isInstanceOf(NullPointerException.class).hasMessage("host");
        assertThatThrownBy(() -> server("db.example", 3306, null, "", ""))
            .isInstanceOf(NullPointerException.class).hasMessage("database");
        assertThatThrownBy(() -> server("db.example", 3306, "app", null, ""))
            .isInstanceOf(NullPointerException.class).hasMessage("password");
        assertThatThrownBy(() -> server("db.example", 3306, "app", "", null))
            .isInstanceOf(NullPointerException.class).hasMessage("tablePrefix");
        assertThatThrownBy(() -> StorageSettings.Server.of("db.example", 3306, "app", null, "",
            StorageSettings.SslMode.DISABLE)).isInstanceOf(NullPointerException.class).hasMessage("user");
        assertThatThrownBy(() -> StorageSettings.Server.of("db.example", 3306, "app", "shop", "", null))
            .isInstanceOf(NullPointerException.class).hasMessage("sslMode");
        assertThatThrownBy(() -> POOL.connectionTimeout(null))
            .isInstanceOf(NullPointerException.class).hasMessage("connectionTimeout");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "_p", "p_", "a", "p1_", "abcdefghijklmnopqrstuvwxyz012345"})
    void aPrefixOfLowercaseLettersDigitsAndUnderscoresIsAccepted(String prefix) {
        assertThat(server(prefix).tablePrefix()).isEqualTo(prefix);
        assertThat(StorageSettings.requirePrefix(prefix)).isEqualTo(prefix);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e1_", "P-", "Pp_", "p-", "p ", "p_;", "abcdefghijklmnopqrstuvwxyz0123456"})
    void aPrefixOutsideThePatternIsRefused(String prefix) {
        String message = "tablePrefix is \"" + prefix + "\"; use up to 32 lowercase letters, digits and"
            + " underscores, starting with a letter or underscore";
        assertThatThrownBy(() -> server(prefix))
            .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThatThrownBy(() -> StorageSettings.requirePrefix(prefix))
            .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema_version", "_v", "shop_schema_version", "v2",
        "abcdefghijklmnopqrstuvwxyz0123456789_abcdefghijklmnopq"})
    void aVersionTableOfUpTo54LowercaseLettersDigitsAndUnderscoresIsAccepted(String table) {
        assertThat(StorageSettings.sqlite("data.db").versionTable(table).versionTable()).isEqualTo(table);
        assertThat(StorageSettings.sqlite("data.db").versionTable(table).versionTableFor("Shop")).isEqualTo(table);
        assertThat(mariaDb("p_").versionTable(table).versionTableFor("Shop")).isEqualTo(table);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1v", "Versions", "shop-versions", "shop versions", "shop;", "shop.versions",
        "abcdefghijklmnopqrstuvwxyz0123456789_abcdefghijklmnopqr"})
    void aVersionTableOutsideThePatternIsRefused(String table) {
        assertThatThrownBy(() -> StorageSettings.sqlite("data.db").versionTable(table))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("versionTable is \"" + table + "\"; use 1 to 54 lowercase letters, digits and underscores,"
                + " starting with a letter or underscore");
    }

    @Test
    void theDefaultVersionTableIsNamedAfterThePluginOnAServerAndIsSchemaVersionOnSqlite() {
        assertThat(StorageSettings.sqlite("data.db").versionTableFor("ShopPlugin")).isEqualTo("schema_version");
        assertThat(mariaDb("").versionTableFor("ShopPlugin")).isEqualTo("shopplugin_schema_version");
        assertThat(mariaDb("p_").versionTableFor("ShopPlugin")).isEqualTo("p_shopplugin_schema_version");
        assertThat(mariaDb("p_").versionTableFor("Shop-Plugin 2.x")).isEqualTo("p_shop_plugin_2_x_schema_version");
        assertThat(mariaDb("").versionTableFor("2Shop")).isEqualTo("_2shop_schema_version");
        assertThat(mariaDb("p_").versionTableFor("2Shop")).isEqualTo("p_2shop_schema_version");
        assertThat(mariaDb("p_").versionTableFor("Äpfel")).isEqualTo("p__pfel_schema_version");
    }

    @Test
    void pluginNamesThatDifferOnlyInReplacedOrCutCharactersShareADefaultVersionTable() {
        String longest = "abcdefghijklmnopqrstuvwxyz012345";

        assertThat(mariaDb("p_").versionTableFor("My-Plugin")).isEqualTo(mariaDb("p_").versionTableFor("My_Plugin"));
        assertThat(mariaDb(longest).versionTableFor("EconomyShop"))
            .isEqualTo(mariaDb(longest).versionTableFor("EconomyBank"));
    }

    @Test
    void aLongPluginNameIsCutSoTheDefaultVersionTableFitsItsLimit() {
        String longest = "abcdefghijklmnopqrstuvwxyz012345";

        assertThat(mariaDb(longest).versionTableFor("ShopPlugin")).isEqualTo(longest + "shopplu_schema_version")
            .hasSize(54);
        assertThat(mariaDb("").versionTableFor("a".repeat(60))).isEqualTo("a".repeat(39) + "_schema_version")
            .hasSize(54);
    }

    @ParameterizedTest
    @ValueSource(strings = {"app", "App_2", "a$b", "app-data", "a",
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ab"})
    void aDatabaseNameOfUpTo64SafeCharactersIsAccepted(String database) {
        assertThat(server("db.example", 3306, database, "", "").database()).isEqualTo(database);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "app data", "app?x=1", "app/x", "app;", "äpp",
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abc"})
    void aDatabaseNameOutsideThePatternIsRefused(String database) {
        assertThatThrownBy(() -> server("db.example", 3306, database, "", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("database is \"" + database + "\"; use up to 64 letters, digits, underscores, dollar signs"
                + " and hyphens");
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "db.example", "10.0.0.5", "[::1]", "[fe80::1:2]", "db-1.internal",
        "db_1.internal"})
    void aHostNameOrAddressIsAccepted(String host) {
        assertThat(server(host, 3306, "app", "", "").host()).isEqualTo(host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "db.example/other", "db.example?user=root", "a&b", "db example", "db\t",
        "db\nexample", "db.example#", "u@db.example", "a,b", "a;b", "db.example:9999", "::1", "[::1", "[db]",
        "db example", "dé.example"})
    void aBlankHostOrOneThatCouldChangeTheUrlIsRefused(String host) {
        assertThatThrownBy(() -> server(host, 3306, "app", "", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("host is \"" + host + "\"; expected a host name, an IPv4 address or an IPv6 address in"
                + " brackets such as [::1]");
    }

    @Test
    void aPortOutsideOneTo65535IsRefused() {
        assertThatCode(() -> server("db.example", 1, "app", "", "")).doesNotThrowAnyException();
        assertThatCode(() -> server("db.example", 65535, "app", "", "")).doesNotThrowAnyException();
        assertThatThrownBy(() -> server("db.example", 0, "app", "", ""))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("port is 0; expected 1 to 65535");
        assertThatThrownBy(() -> server("db.example", 70000, "app", "", ""))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("port is 70000; expected 1 to 65535");
    }

    @Test
    void outOfRangePoolValuesAreRefused() {
        StorageSettings.Pool pool = StorageSettings.Pool.of(8);

        assertThatCode(() -> StorageSettings.Pool.of(1).minIdle(0).connectionTimeout(Duration.ofMillis(250)))
            .doesNotThrowAnyException();
        assertThatCode(() -> StorageSettings.Pool.of(4).minIdle(4).connectionTimeout(Duration.ofSeconds(1)))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> StorageSettings.Pool.of(0))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("maxSize is 0; expected 1 or more");
        assertThatThrownBy(() -> pool.maxSize(0))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("maxSize is 0; expected 1 or more");
        assertThatThrownBy(() -> pool.minIdle(50))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("minIdle is 50; expected 0 to 8");
        assertThatThrownBy(() -> pool.minIdle(-1))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("minIdle is -1; expected 0 to 8");
        assertThatThrownBy(() -> pool.minIdle(4).maxSize(3))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("minIdle is 4; expected 0 to 3");
        assertThatThrownBy(() -> pool.connectionTimeout(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("connectionTimeout is PT0S; expected 250ms or more");
        assertThatThrownBy(() -> pool.connectionTimeout(Duration.ofMillis(-1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("connectionTimeout is PT-0.001S; expected 250ms or more");
        assertThatThrownBy(() -> pool.connectionTimeout(Duration.ofNanos(500)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("connectionTimeout is PT0.0000005S; expected 250ms or more");
        assertThatThrownBy(() -> pool.connectionTimeout(Duration.ofMillis(249)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("connectionTimeout is PT0.249S; expected 250ms or more");
    }

    @Test
    void theSslModesAreDisablePreferTrustAndVerifyFull() {
        assertThat(StorageSettings.SslMode.values()).containsExactly(StorageSettings.SslMode.DISABLE,
            StorageSettings.SslMode.PREFER, StorageSettings.SslMode.TRUST, StorageSettings.SslMode.VERIFY_FULL);
    }
}
