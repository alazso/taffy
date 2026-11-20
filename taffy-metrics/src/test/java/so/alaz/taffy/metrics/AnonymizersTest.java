package so.alaz.taffy.metrics;

import dev.faststats.ErrorTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class AnonymizersTest {

    private static final int JDBC = 0;
    private static final int URI_CREDENTIALS = 1;
    private static final int EMAIL = 2;
    private static final int BEARER = 3;
    private static final int AWS_KEY = 4;
    private static final int UUID = 5;
    private static final int MYSQL_ACCOUNT = 6;
    private static final int QUERY_SECRET = 7;
    private static final int SECRET_KEY = 8;
    private static final int SQL = 9;
    private static final int WINDOWS_PATH = 10;
    private static final int UNIX_PATH = 11;
    private static final int HOST = 12;

    @Test
    void thereAreThirteenRulesOfAPatternAndAReplacement() {
        assertThat(Anonymizers.RULES).hasDimensions(13, 2);
    }

    @Test
    void aJdbcUrlIsRedacted() {
        assertThat(apply(JDBC, "cannot open jdbc:mysql://db.local:3306/game?user=root failed"))
            .isEqualTo("cannot open jdbc:[redacted] failed");
        assertThat(apply(JDBC, "the jdbc driver is missing")).isEqualTo("the jdbc driver is missing");
    }

    @Test
    void aJdbcUrlWithCredentialsIsRedactedWhole() {
        assertThat(Anonymizers.anonymize("cannot open jdbc:mysql://root:pw@db.example.com:3306/game?password=x now"))
            .isEqualTo("cannot open jdbc:[redacted] now");
        assertThat(Anonymizers.anonymize("jdbc:mysql://root@db.example.com/game?password=x"))
            .isEqualTo("jdbc:[redacted]");
    }

    @Test
    void theCredentialsOfAUriOfAnySchemeAreHidden() {
        assertThat(apply(URI_CREDENTIALS, "redis://default:hunter2@cache:6379 refused"))
            .isEqualTo("redis://[credentials hidden]@cache:6379 refused");
        assertThat(apply(URI_CREDENTIALS, "redis://:hunter2@cache:6379 refused"))
            .isEqualTo("redis://[credentials hidden]@cache:6379 refused");
        assertThat(apply(URI_CREDENTIALS, "open mongodb+srv://app:s3cr3t@cluster0.example.net/game"))
            .isEqualTo("open mongodb+srv://[credentials hidden]@cluster0.example.net/game");
        assertThat(apply(URI_CREDENTIALS, "AMQP://guest:guest@queue"))
            .isEqualTo("AMQP://[credentials hidden]@queue");
    }

    @Test
    void aUriWithoutAPasswordIsLeftAsWritten() {
        assertThat(apply(URI_CREDENTIALS, "redis://cache:6379 refused")).isEqualTo("redis://cache:6379 refused");
        assertThat(apply(URI_CREDENTIALS, "GET http://localhost:8080/users/a@b failed"))
            .isEqualTo("GET http://localhost:8080/users/a@b failed");
        assertThat(apply(URI_CREDENTIALS, "ratio 3:1 for a@b")).isEqualTo("ratio 3:1 for a@b");
    }

    @Test
    void anEmailAddressIsHidden() {
        assertThat(apply(EMAIL, "mail admin@example.org failed")).isEqualTo("mail [email hidden] failed");
        assertThat(apply(EMAIL, "mail first.last@mail.example.co.uk.")).isEqualTo("mail [email hidden].");
        assertThat(apply(EMAIL, "a@b@c.com")).isEqualTo("a@[email hidden]");
    }

    @Test
    void anAtSignWithoutADottedDomainIsLeftAsWritten() {
        assertThat(apply(EMAIL, "ping @everyone at 5@once")).isEqualTo("ping @everyone at 5@once");
        assertThat(apply(EMAIL, "y@a.b.c")).isEqualTo("y@a.b.c");
    }

    @Test
    void aBearerTokenIsHidden() {
        assertThat(apply(BEARER, "Authorization: Bearer abc.DEF-123_~+/="))
            .isEqualTo("Authorization: Bearer [token hidden]");
        assertThat(apply(BEARER, "the bearer of the item")).isEqualTo("the bearer of the item");
    }

    @Test
    void anAwsAccessKeyIdIsHidden() {
        assertThat(apply(AWS_KEY, "key AKIAABCDEFGHIJKLMNOP used")).isEqualTo("key [aws-key hidden] used");
        assertThat(apply(AWS_KEY, "key AKIA1234 used")).isEqualTo("key AKIA1234 used");
    }

    @Test
    void aUuidIsHidden() {
        assertThat(apply(UUID, "player 123e4567-e89b-42d3-a456-426614174000 left"))
            .isEqualTo("player [uuid hidden] left");
    }

    @Test
    void anUpperCaseUuidIsHidden() {
        assertThat(apply(UUID, "player 123E4567-E89B-42D3-A456-426614174000 left"))
            .isEqualTo("player [uuid hidden] left");
    }

    @Test
    void aUuidWithoutDashesIsHidden() {
        assertThat(apply(UUID, "player 123e4567e89b42d3a456426614174000 left")).isEqualTo("player [uuid hidden] left");
        assertThat(apply(UUID, "player 123E4567E89B42D3A456426614174000 left")).isEqualTo("player [uuid hidden] left");
    }

    @Test
    void aHexRunOfAnotherLengthIsLeftAsWritten() {
        assertThat(apply(UUID, "commit 3f786850e387550fdab836ed7e6dc881de23001b"))
            .isEqualTo("commit 3f786850e387550fdab836ed7e6dc881de23001b");
        assertThat(apply(UUID, "colour deadbeef of 123e4567-e89b")).isEqualTo("colour deadbeef of 123e4567-e89b");
    }

    @Test
    void theUserAndHostOfAMysqlAccountAreHidden() {
        assertThat(apply(MYSQL_ACCOUNT, "Access denied for user 'mc_prod'@'db.examplecraft.net' (using password: YES)"))
            .isEqualTo("Access denied for user [user hidden]@[host hidden] (using password: YES)");
        assertThat(apply(MYSQL_ACCOUNT, "The user specified as a definer ('root'@'%') does not exist"))
            .isEqualTo("The user specified as a definer ([user hidden]@[host hidden]) does not exist");
    }

    @Test
    void quotedWordsWithoutAnAtSignBetweenThemAreLeftAsWritten() {
        assertThat(apply(MYSQL_ACCOUNT, "Unknown column 'kit' in 'field list'"))
            .isEqualTo("Unknown column 'kit' in 'field list'");
        assertThat(apply(MYSQL_ACCOUNT, "'a' @ 'b'")).isEqualTo("'a' @ 'b'");
    }

    @Test
    void theMysqlAccessDeniedMessageKeepsItsWordsUnderEveryRule() {
        String denied = "Access denied for user 'mc_prod'@'db.examplecraft.net' (using password: YES)";

        assertThat(Anonymizers.anonymize(denied))
            .isEqualTo("Access denied for user [user hidden]@[host hidden] (using password: YES)");
    }

    @Test
    void aSecretQueryValueIsRedactedAndTheKeyKept() {
        assertThat(apply(QUERY_SECRET, "GET /hook?api_key=s3cr3t&page=2"))
            .isEqualTo("GET /hook?api_key=[redacted]&page=2");
        assertThat(apply(QUERY_SECRET, "GET /hook?x=1&token=abc")).isEqualTo("GET /hook?x=1&token=[redacted]");
        assertThat(apply(QUERY_SECRET, "GET /hook?apikey=abc&secret=def"))
            .isEqualTo("GET /hook?apikey=[redacted]&secret=[redacted]");
        assertThat(apply(QUERY_SECRET, "GET /hook?page=2")).isEqualTo("GET /hook?page=2");
    }

    @Test
    void theValueOfASecretKeyIsRedactedAndTheKeyKept() {
        assertThat(apply(SECRET_KEY, "login failed: password=hunter2, retrying"))
            .isEqualTo("login failed: password=[redacted], retrying");
        assertThat(apply(SECRET_KEY, "line 12: bot-token: abc.DEF-123")).isEqualTo("line 12: bot-token: [redacted]");
        assertThat(apply(SECRET_KEY, "body {\"api_key\": \"k-1\", \"page\": 2}"))
            .isEqualTo("body {\"api_key\": [redacted], \"page\": 2}");
        assertThat(apply(SECRET_KEY, "db_password = 'two words' rejected"))
            .isEqualTo("db_password = [redacted] rejected");
        assertThat(apply(SECRET_KEY, "SECRET=a PWD=b passwd=c apikey=d access-key=e private_key=f client_secret:g"))
            .isEqualTo("SECRET=[redacted] PWD=[redacted] passwd=[redacted] apikey=[redacted] access-key=[redacted]"
                + " private_key=[redacted] client_secret:[redacted]");
    }

    @Test
    void aSecretWordWithoutAValueIsLeftAsWritten() {
        assertThat(apply(SECRET_KEY, "Access denied for user (using password: YES)"))
            .isEqualTo("Access denied for user (using password: YES)");
        assertThat(apply(SECRET_KEY, "(using password: NO)")).isEqualTo("(using password: NO)");
        assertThat(apply(SECRET_KEY, "password reset failed for 3 tokens"))
            .isEqualTo("password reset failed for 3 tokens");
        assertThat(apply(SECRET_KEY, "the secret is out")).isEqualTo("the secret is out");
        assertThat(apply(SECRET_KEY, "Authorization: Bearer [token hidden]"))
            .isEqualTo("Authorization: Bearer [token hidden]");
    }

    @Test
    void anSqlStatementIsHiddenToTheEndOfTheLine() {
        assertThat(apply(SQL, "failed: select * from players where name = 'x'\nnext"))
            .isEqualTo("failed: [sql hidden]\nnext");
        assertThat(apply(SQL, "the selection is empty")).isEqualTo("the selection is empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "INSERT INTO balances (shop, amount) VALUES (12, 5)",
        "insert or replace into balances values (12, 5)",
        "INSERT IGNORE INTO balances VALUES (12, 5)",
        "UPDATE balances SET amount = 5 WHERE shop = 12",
        "update `shop`.`balances`\tset amount = 5",
        "DELETE FROM balances WHERE shop = 12",
        "REPLACE INTO balances VALUES (12, 5)",
        "MERGE INTO balances KEY (shop) VALUES (12, 5)",
        "CREATE TABLE IF NOT EXISTS balances (shop INT)",
        "ALTER TABLE balances ADD COLUMN owner VARCHAR(36)",
        "drop table balances",
        "SELECT amount FROM balances WHERE shop = 12",
    })
    void aStatementOfEachShapeIsHidden(String statement) {
        assertThat(apply(SQL, "failed: " + statement + "\nnext")).isEqualTo("failed: [sql hidden]\nnext");
    }

    @Test
    void anSqlStatementIsHiddenForAtMost200CharactersPastItsStart() {
        assertThat(apply(SQL, "DELETE FROM " + "a".repeat(200) + "tail")).isEqualTo("[sql hidden]tail");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Failed to update balance for shop 12: timeout after 5003ms",
        "Could not delete file",
        "insert slot 3 failed",
        "select a kit first",
        "update check failed",
        "Cannot drop the table item here",
        "selected 3 rows, inserted 2, updated 1 and deleted none",
    })
    void anSqlVerbWithoutAStatementIsLeftAsWritten(String line) {
        assertThat(apply(SQL, line)).isEqualTo(line);
    }

    @Test
    void aMessageWithAnSqlVerbKeepsItsWordsUnderEveryRule() {
        assertThat(Anonymizers.anonymize("Could not delete C:\\srv\\mc\\plugins\\X\\data.db"))
            .isEqualTo("Could not delete [path hidden]");
    }

    @Test
    void aWindowsPathIsHidden() {
        assertThat(apply(WINDOWS_PATH, "cannot read C:\\Server\\plugins\\data.db now"))
            .isEqualTo("cannot read [path hidden] now");
        assertThat(apply(WINDOWS_PATH, "ratio C:3 and a\\b")).isEqualTo("ratio C:3 and a\\b");
    }

    @Test
    void anAbsoluteUnixPathIsHidden() {
        assertThat(apply(UNIX_PATH, "cannot read /home/mc/server/plugins/Shop/data.db now"))
            .isEqualTo("cannot read [path hidden] now");
        assertThat(apply(UNIX_PATH, "open(/etc/passwd) and '/var/lib/mysql/'"))
            .isEqualTo("open([path hidden]) and '[path hidden]'");
    }

    @Test
    void aSlashThatStartsNoAbsolutePathIsLeftAsWritten() {
        assertThat(apply(UNIX_PATH, "TPS 20/20, read/write and/or 2026/11/19"))
            .isEqualTo("TPS 20/20, read/write and/or 2026/11/19");
        assertThat(apply(UNIX_PATH, "run /shop open first")).isEqualTo("run /shop open first");
        assertThat(apply(UNIX_PATH, "<red>no</red> <b>kit</b>")).isEqualTo("<red>no</red> <b>kit</b>");
        assertThat(apply(UNIX_PATH, "GET https://example.org/a/b and plugins/Shop/data.db"))
            .isEqualTo("GET https://example.org/a/b and plugins/Shop/data.db");
    }

    @Test
    void aHostNameIsHidden() {
        assertThat(apply(HOST, "java.net.UnknownHostException: db.examplecraft.net"))
            .isEqualTo("java.net.UnknownHostException: [host hidden]");
        assertThat(apply(HOST, "connect to DB.Internal.ExampleCraft.NET:3306 timed out."))
            .isEqualTo("connect to [host hidden]:3306 timed out.");
        assertThat(apply(HOST, "GET https://api.example.com/v1/items failed"))
            .isEqualTo("GET https://[host hidden]/v1/items failed");
        assertThat(apply(HOST, "no route to cache-1.internal.")).isEqualTo("no route to [host hidden].");
    }

    @Test
    void aDottedNameThatIsNoHostNameIsLeftAsWritten() {
        assertThat(apply(HOST, "ClassNotFoundException: org.apache.commons.io.FileUtils"))
            .isEqualTo("ClassNotFoundException: org.apache.commons.io.FileUtils");
        assertThat(apply(HOST, "com.zaxxer.hikari.pool.HikariPool at so.alaz.taffy.Text"))
            .isEqualTo("com.zaxxer.hikari.pool.HikariPool at so.alaz.taffy.Text");
        assertThat(apply(HOST, "config.yml and data.db on 1.21.4, e.g. kits.starter"))
            .isEqualTo("config.yml and data.db on 1.21.4, e.g. kits.starter");
        assertThat(apply(HOST, "connect to cache:6379 or localhost failed"))
            .isEqualTo("connect to cache:6379 or localhost failed");
    }

    @Test
    void theRulesTogetherHideEachPartOfAConnectionString() {
        assertThat(Anonymizers.anonymize("redis://default:hunter2@cache.examplecraft.net:6379 refused"))
            .isEqualTo("redis://[credentials hidden]@[host hidden]:6379 refused");
        assertThat(Anonymizers.anonymize("GET https://api.example.com/v1/hook?token=abc&page=2 returned 500"))
            .isEqualTo("GET https://[host hidden]/v1/hook?token=[redacted]&page=2 returned 500");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Could not save the kit of shop 12: timeout after 5003ms",
        "Failed to update balance for shop 12: timeout after 5003ms",
        "select a kit first",
        "Could not delete file",
        "insert slot 3 failed",
        "update check failed",
        "Failed to load config.yml: line 3, column 5",
        "Kit 'starter' is on cooldown (30s left), see /kit list.",
        "No permission to open the menu. Ask an admin/owner.",
        "java.lang.IllegalStateException: Player Steve is offline",
        "Unknown sound minecraft:entity.villager.no in rewards.yml",
    })
    void anOrdinaryMessageIsLeftAsWrittenByEveryRule(String line) {
        assertThat(Anonymizers.anonymize(line)).isEqualTo(line);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "a", "a.", "a-", "a@", "a.a@", "x@a.", "/a", "/", "://", "a://", "x://a:", "a:", "'", "'a'@", "0", "0-", "f",
        "select ", "select a from ", "update a ", "update a set", "insert  ", "insert or ", "create table ",
        "password", "password=", "password \"", "token:", " ",
        "\t", "\\", "C:\\", ".com", "a.com.", "jdbc:", "Bearer ", "?token=", "\"", "-", ".",
    })
    void aLongAdversarialTextIsAnonymizedQuickly(String unit) {
        String text = unit.repeat(100_000 / unit.length() + 1);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Anonymizers.anonymize(text);
            Anonymizers.anonymize("x@" + text);
            Anonymizers.anonymize("x://" + text);
            Anonymizers.anonymize("password=" + text);
            Anonymizers.anonymize("/a/" + text);
        });
    }

    @Test
    void applyAddsTheRulesToTheTrackerItIsGiven() {
        ErrorTracker tracker = ErrorTracker.contextUnaware();

        assertThat(Anonymizers.apply(tracker)).isSameAs(tracker);
        assertThat(tracker.isContextAttached()).isFalse();
    }

    private static String apply(int rule, String line) {
        return line.replaceAll(Anonymizers.RULES[rule][0], Anonymizers.RULES[rule][1]);
    }
}
