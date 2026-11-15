package so.alaz.taffy.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class StorageSettingsTest {

    private static final StorageSettings.Pool POOL = new StorageSettings.Pool(8, 2, Duration.ofSeconds(10));

    private static StorageSettings.Server server(String host, int port, String database, String password,
                                                 String prefix) {
        return new StorageSettings.Server(host, port, database, "shop", password,
            StorageSettings.SslMode.VERIFY_FULL, prefix);
    }

    private static StorageSettings.Server server(String prefix) {
        return server("db.example", 3307, "app", "0451", prefix);
    }

    @Test
    void settingsWithTheSameValuesAreEqual() {
        StorageSettings first = new StorageSettings(Dialect.MARIADB, "data.db", server("p_"), POOL, 2, 5);
        StorageSettings second = new StorageSettings(Dialect.MARIADB, "data.db", server("p_"), POOL, 2, 5);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(new StorageSettings(Dialect.MARIADB, "data.db", server("q_"), POOL, 2, 5));
        assertThat(first.server()).isEqualTo(server("p_"));
        assertThat(first.pool()).isEqualTo(new StorageSettings.Pool(8, 2, Duration.ofSeconds(10)));
        assertThat(first.asyncThreads()).isEqualTo(2);
        assertThat(first.backupKeep()).isEqualTo(5);
    }

    @Test
    void theServerToStringHidesASetPasswordAndShowsAnEmptyOne() {
        assertThat(server("p_")).hasToString("Server[host=db.example, port=3307, database=app, user=shop,"
            + " password=(set), sslMode=VERIFY_FULL, tablePrefix=p_]");
        assertThat(server("db.example", 3307, "app", "", "p_")).hasToString("Server[host=db.example,"
            + " port=3307, database=app, user=shop, password=, sslMode=VERIFY_FULL, tablePrefix=p_]");
        assertThat(new StorageSettings(Dialect.MYSQL, "data.db", server("p_"), POOL, 2, 5).toString())
            .doesNotContain("0451").contains("password=(set)");
    }

    @Test
    void sqliteNeedsNoServerAndHasNoTablePrefix() {
        StorageSettings sqlite = new StorageSettings(Dialect.SQLITE, "data.db", null, POOL, 1, 0);

        assertThat(sqlite.server()).isNull();
        assertThat(sqlite.sqliteFile()).isEqualTo("data.db");
        assertThat(sqlite.tablePrefix()).isEmpty();
        assertThat(new StorageSettings(Dialect.SQLITE, "data.db", server("p_"), POOL, 1, 0).tablePrefix())
            .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = "SQLITE", mode = EnumSource.Mode.EXCLUDE)
    void aServerDialectUsesTheServerPrefixAndNeedsAServer(Dialect dialect) {
        assertThat(new StorageSettings(dialect, "", server("p_"), POOL, 2, 5).tablePrefix()).isEqualTo("p_");
        assertThat(new StorageSettings(dialect, "", server(""), POOL, 2, 5).tablePrefix()).isEmpty();
        assertThatThrownBy(() -> new StorageSettings(dialect, "data.db", null, POOL, 2, 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("server is missing; " + dialect.id() + " needs a server");
    }

    @Test
    void sqliteRefusesABlankFile() {
        assertThatThrownBy(() -> new StorageSettings(Dialect.SQLITE, " ", null, POOL, 2, 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("sqliteFile is blank; name the SQLite database file");
    }

    @Test
    void threadAndBackupCountsOutOfRangeAreRefused() {
        assertThatThrownBy(() -> new StorageSettings(Dialect.SQLITE, "data.db", null, POOL, 0, 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("asyncThreads is 0; expected 1 or more");
        assertThatThrownBy(() -> new StorageSettings(Dialect.SQLITE, "data.db", null, POOL, 2, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("backupKeep is -1; expected 0 or more");
    }

    @Test
    void nullReferencesAreRefusedByName() {
        assertThatThrownBy(() -> new StorageSettings(null, "data.db", null, POOL, 2, 5))
            .isInstanceOf(NullPointerException.class).hasMessage("dialect");
        assertThatThrownBy(() -> new StorageSettings(Dialect.SQLITE, null, null, POOL, 2, 5))
            .isInstanceOf(NullPointerException.class).hasMessage("sqliteFile");
        assertThatThrownBy(() -> new StorageSettings(Dialect.SQLITE, "data.db", null, null, 2, 5))
            .isInstanceOf(NullPointerException.class).hasMessage("pool");
        assertThatThrownBy(() -> server(null, 3306, "app", "", ""))
            .isInstanceOf(NullPointerException.class).hasMessage("host");
        assertThatThrownBy(() -> server("db.example", 3306, null, "", ""))
            .isInstanceOf(NullPointerException.class).hasMessage("database");
        assertThatThrownBy(() -> server("db.example", 3306, "app", null, ""))
            .isInstanceOf(NullPointerException.class).hasMessage("password");
        assertThatThrownBy(() -> server("db.example", 3306, "app", "", null))
            .isInstanceOf(NullPointerException.class).hasMessage("tablePrefix");
        assertThatThrownBy(() -> new StorageSettings.Server("db.example", 3306, "app", null, "",
            StorageSettings.SslMode.DISABLE, "")).isInstanceOf(NullPointerException.class).hasMessage("user");
        assertThatThrownBy(() -> new StorageSettings.Server("db.example", 3306, "app", "shop", "", null, ""))
            .isInstanceOf(NullPointerException.class).hasMessage("sslMode");
        assertThatThrownBy(() -> new StorageSettings.Pool(8, 2, null))
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
        "db\u00a0example", "d\u00e9.example"})
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
        assertThatCode(() -> new StorageSettings.Pool(1, 0, Duration.ofMillis(250))).doesNotThrowAnyException();
        assertThatCode(() -> new StorageSettings.Pool(4, 4, Duration.ofSeconds(1))).doesNotThrowAnyException();
        assertThatThrownBy(() -> new StorageSettings.Pool(0, 0, Duration.ofSeconds(10)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("maxSize is 0; expected 1 or more");
        assertThatThrownBy(() -> new StorageSettings.Pool(8, 50, Duration.ofSeconds(10)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("minIdle is 50; expected 0 to 8");
        assertThatThrownBy(() -> new StorageSettings.Pool(8, -1, Duration.ofSeconds(10)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("minIdle is -1; expected 0 to 8");
        assertThatThrownBy(() -> new StorageSettings.Pool(8, 2, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("connectionTimeout is PT0S; expected 250ms or more");
        assertThatThrownBy(() -> new StorageSettings.Pool(8, 2, Duration.ofMillis(-1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("connectionTimeout is PT-0.001S; expected 250ms or more");
        assertThatThrownBy(() -> new StorageSettings.Pool(8, 2, Duration.ofNanos(500)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("connectionTimeout is PT0.0000005S; expected 250ms or more");
        assertThatThrownBy(() -> new StorageSettings.Pool(8, 2, Duration.ofMillis(249)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("connectionTimeout is PT0.249S; expected 250ms or more");
    }

    @Test
    void theSslModesAreDisableTrustAndVerifyFull() {
        assertThat(StorageSettings.SslMode.values()).containsExactly(StorageSettings.SslMode.DISABLE,
            StorageSettings.SslMode.TRUST, StorageSettings.SslMode.VERIFY_FULL);
    }
}
