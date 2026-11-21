package so.alaz.taffy.metrics;

import com.google.gson.JsonArray;
import dev.faststats.ErrorTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
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
    private static final int POSTGRES_USER = 7;
    private static final int QUERY_SECRET = 8;
    private static final int SECRET_KEY = 9;
    private static final int SQL = 10;
    private static final int WINDOWS_PATH = 11;
    private static final int UNIX_PATH = 12;
    private static final int DISCORD_WEBHOOK = 13;
    private static final int HOST = 14;
    private static final int DATABASE = 15;
    private static final int URI_HOST = 16;
    private static final int NAMED_HOST = 17;

    private static final String WEBHOOK_ID = "123456789012345678";
    private static final String WEBHOOK_TOKEN = "abcDEF-ghi_JKL";

    @Test
    void thereAreEighteenRulesOfAPatternAndAReplacement() {
        assertThat(Anonymizers.RULES).hasDimensions(18, 2);
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
    void aJdbcUrlOfAnAbsolutePathWithASpaceIsRedactedWhole() {
        assertThat(apply(JDBC, "Failed to get driver instance for jdbcUrl=jdbc:sqlite:C:\\Users\\John Smith\\server"
            + "\\plugins\\Shop\\data.db")).isEqualTo("Failed to get driver instance for jdbcUrl=jdbc:[redacted]");
        assertThat(apply(JDBC, "cannot open jdbc:sqlite:C:/Users/John Smith/server/plugins/Shop/data.db now"))
            .isEqualTo("cannot open jdbc:[redacted] now");
        assertThat(apply(JDBC, "cannot open jdbc:sqlite:/home/john/My Server/plugins/Shop/data.db now"))
            .isEqualTo("cannot open jdbc:[redacted] now");
        assertThat(apply(JDBC, "cannot open jdbc:h2:file:C:\\Mc Server\\plugins\\Shop\\data;MODE=MySQL now"))
            .isEqualTo("cannot open jdbc:[redacted] now");
        assertThat(apply(JDBC, "cannot open jdbc:sqlite:/srv/data.db is locked by a/b"))
            .isEqualTo("cannot open jdbc:[redacted] is locked by a/b");
        assertThat(apply(JDBC, "cannot open jdbc:mysql://db.local:3306/game of the a/b test"))
            .isEqualTo("cannot open jdbc:[redacted] of the a/b test");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Failed to get driver instance for jdbcUrl=jdbc:sqlite:C:\\Users\\John Smith\\server\\plugins\\Shop\\data.db",
        "Failed to get driver instance for jdbcUrl=jdbc:sqlite:C:/Users/John Smith/server/plugins/Shop/data.db",
        "Failed to get driver instance for jdbcUrl=jdbc:sqlite:/home/john/John Smith/plugins/Shop/data.db",
    })
    void aTrackerWithTheRulesSendsNoPartOfAJdbcPathWithASpace(String message) throws ReflectiveOperationException {
        assertThat(Anonymizers.anonymize(message))
            .isEqualTo("Failed to get driver instance for jdbcUrl=jdbc:[redacted]");
        assertThat(sent(message)).doesNotContain("John", "Smith", "plugins", "Shop", "data.db");
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
    void aPasswordThatHoldsAnAtSignIsHiddenWhole() {
        assertThat(apply(URI_CREDENTIALS, "redis://user:p@ss@cache:6379 refused"))
            .isEqualTo("redis://[credentials hidden]@cache:6379 refused");
        assertThat(apply(URI_CREDENTIALS, "redis://user:p@ss@cache:6379/0 and admin@host"))
            .isEqualTo("redis://[credentials hidden]@cache:6379/0 and admin@host");
    }

    @Test
    void aPasswordThatHoldsASlashIsHiddenWhole() {
        assertThat(apply(URI_CREDENTIALS, "redis://default:hun/ter2@cache:6379 failed"))
            .isEqualTo("redis://[credentials hidden]@cache:6379 failed");
        assertThat(apply(URI_CREDENTIALS, "redis://user:pa/ss@cache:6379 refused"))
            .isEqualTo("redis://[credentials hidden]@cache:6379 refused");
        assertThat(apply(URI_CREDENTIALS, "redis://user:/p@/ss/@cache:6379/0 refused"))
            .isEqualTo("redis://[credentials hidden]@cache:6379/0 refused");
        assertThat(Anonymizers.anonymize("redis://default:hun/ter2@cache:6379 failed"))
            .isEqualTo("redis://[credentials hidden]@[host hidden]:6379 failed");
    }

    @Test
    void aPasswordWithoutASlashIsHiddenAtAnyLength() {
        assertThat(apply(URI_CREDENTIALS, "redis://user:" + "p".repeat(300) + "@cache:6379 refused"))
            .isEqualTo("redis://[credentials hidden]@cache:6379 refused");
    }

    @Test
    void aPortAndAPathBeforeAnAtSignAreLeftAsWritten() {
        assertThat(apply(URI_CREDENTIALS, "GET http://localhost:8080/users/a@b failed"))
            .isEqualTo("GET http://localhost:8080/users/a@b failed");
        assertThat(apply(URI_CREDENTIALS, "redis://user:123/ab@cache:6379 refused"))
            .isEqualTo("redis://user:123/ab@cache:6379 refused");
        assertThat(apply(URI_CREDENTIALS, "redis://user:123456/ab@cache:6379 refused"))
            .isEqualTo("redis://[credentials hidden]@cache:6379 refused");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "servers=[redis://cache-1:6379,redis://cache-2:6379],listener=so.alaz.shop.Sync@1b2c3d4",
        "{\"url\":\"redis://cache:6379\",\"owner\":\"Notch\",\"note\":\"x@y\"}",
        "{url=redis://cache:6379},handler=Sync@1b2c3d4",
        "GET http://localhost:8080?next=a@b and http://localhost:8080#a@b",
        "redis://cache:6379",
    })
    void aPortBeforeALaterAtSignIsNoPassword(String line) {
        assertThat(apply(URI_CREDENTIALS, line)).isEqualTo(line);
    }

    @Test
    void aPasswordOfDigitsAndASignAUriAllowsIsHidden() {
        assertThat(apply(URI_CREDENTIALS, "redis://user:123,abc@cache:6379 refused"))
            .isEqualTo("redis://[credentials hidden]@cache:6379 refused");
        assertThat(apply(URI_CREDENTIALS, "redis://user:12345@cache:6379 refused"))
            .isEqualTo("redis://[credentials hidden]@cache:6379 refused");
    }

    @Test
    void theRulesTogetherKeepTwoUrisOfOneUnspacedList() {
        assertThat(Anonymizers.anonymize(
            "servers=[redis://cache-1:6379,redis://cache-2:6379],listener=so.alaz.shop.Sync@1b2c3d4"))
            .isEqualTo("servers=[redis://[host hidden]:6379,redis://[host hidden]:6379],"
                + "listener=so.alaz.shop.Sync@1b2c3d4");
        assertThat(Anonymizers.anonymize("{\"url\":\"redis://cache:6379\",\"note\":\"x@y\"}"))
            .isEqualTo("{\"url\":\"redis://[host hidden]:6379\",\"note\":\"x@y\"}");
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
    void anEmailAddressWithALongLastLabelIsHiddenWhole() {
        assertThat(apply(EMAIL, "mail to john.smith@example.online failed"))
            .isEqualTo("mail to [email hidden] failed");
        assertThat(apply(EMAIL, "mail to john.smith@mail.example.museum failed"))
            .isEqualTo("mail to [email hidden] failed");
        assertThat(Anonymizers.anonymize("mail to john.smith@example.online failed"))
            .isEqualTo("mail to [email hidden] failed");
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
    void aUuidNextToAWordCharacterIsHidden() {
        assertThat(apply(UUID, "Failed to save data_123e4567-e89b-42d3-a456-426614174000"))
            .isEqualTo("Failed to save data_[uuid hidden]");
        assertThat(apply(UUID, "cooldown_123e4567-e89b-42d3-a456-426614174000_daily missing"))
            .isEqualTo("cooldown_[uuid hidden]_daily missing");
        assertThat(apply(UUID, "player123e4567-e89b-42d3-a456-426614174000 left"))
            .isEqualTo("player[uuid hidden] left");
        assertThat(apply(UUID, "key shop:123e4567-e89b-42d3-a456-426614174000x"))
            .isEqualTo("key shop:[uuid hidden]x");
        assertThat(apply(UUID, "data_123e4567e89b42d3a456426614174000.yml")).isEqualTo("data_[uuid hidden].yml");
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
    void theQuotedNameAfterUserOrRoleIsHidden() {
        assertThat(apply(POSTGRES_USER, "FATAL: password authentication failed for user \"mc_prod\""))
            .isEqualTo("FATAL: password authentication failed for user [user hidden]");
        assertThat(apply(POSTGRES_USER, "FATAL: role \"mc_prod\" does not exist"))
            .isEqualTo("FATAL: role [user hidden] does not exist");
        assertThat(apply(POSTGRES_USER,
            "no pg_hba.conf entry for host \"db\", user \"mc_prod\", database \"vouchers\", SSL off"))
            .isEqualTo("no pg_hba.conf entry for host \"db\", user [user hidden], database \"vouchers\", SSL off");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "select a role first",
        "Unknown user given",
        "the user \"said hello\" twice",
        "role \"\" is empty",
        "browser \"chrome\"",
    })
    void theWordsUserAndRoleWithoutAQuotedNameAreLeftAsWritten(String line) {
        assertThat(apply(POSTGRES_USER, line)).isEqualTo(line);
    }

    @Test
    void thePostgresAuthenticationMessageKeepsItsWordsUnderEveryRule() throws ReflectiveOperationException {
        String failed = "FATAL: password authentication failed for user \"mc_prod\"";

        assertThat(Anonymizers.anonymize(failed))
            .isEqualTo("FATAL: password authentication failed for user [user hidden]");
        assertThat(sent(failed)).isEqualTo("FATAL: password authentication failed for user [user hidden]");
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
    void aLongerNameAroundSecretOrTokenIsAKeyBeforeEitherSign() {
        assertThat(apply(SECRET_KEY, "secretKey=abc123 rejected")).isEqualTo("secretKey=[redacted] rejected");
        assertThat(apply(SECRET_KEY, "tokenValue: abc123 rejected")).isEqualTo("tokenValue: [redacted] rejected");
        assertThat(apply(SECRET_KEY, "secretKey: abc123 and tokenValue=abc123"))
            .isEqualTo("secretKey: [redacted] and tokenValue=[redacted]");
        assertThat(apply(SECRET_KEY, "Invalid discord.token: abc123")).isEqualTo("Invalid discord.token: [redacted]");
        assertThat(apply(SECRET_KEY, "Invalid tokens_file: abc123")).isEqualTo("Invalid tokens_file: [redacted]");
        assertThat(apply(SECRET_KEY, "Invalid clientSecret: abc123")).isEqualTo("Invalid clientSecret: [redacted]");
    }

    @Test
    void thePlainWordSecretOrTokenIsAKeyBeforeAnEqualsSign() {
        assertThat(apply(SECRET_KEY, "Invalid token=abc123 given")).isEqualTo("Invalid token=[redacted] given");
        assertThat(apply(SECRET_KEY, "with secret = abc123")).isEqualTo("with secret = [redacted]");
    }

    @Test
    void thePlainWordSecretOrTokenBeforeAColonIsAKeyWhenQuotedOrAtTheStartOfALine() {
        assertThat(apply(SECRET_KEY, "body {\"token\": \"abc123\", \"page\": 2}"))
            .isEqualTo("body {\"token\": [redacted], \"page\": 2}");
        assertThat(apply(SECRET_KEY, "body {'secrets': 'abc123'}")).isEqualTo("body {'secrets': [redacted]}");
        assertThat(apply(SECRET_KEY, "token: abc123")).isEqualTo("token: [redacted]");
        assertThat(apply(SECRET_KEY, "while parsing a block mapping\n  secret: abc123\n  port: 25565"))
            .isEqualTo("while parsing a block mapping\n  secret: [redacted]\n  port: 25565");
        assertThat(apply(SECRET_KEY, "bots:\n\t- token: abc123")).isEqualTo("bots:\n\t- token: [redacted]");
    }

    @Test
    void thePlainWordSecretOrTokenBeforeAColonIsAKeyAfterAnyCharacterButAWordAndASpace() {
        assertThat(apply(SECRET_KEY, "[Shop] Discord login failed, token: MTIzNDU2.abc.def"))
            .isEqualTo("[Shop] Discord login failed, token: [redacted]");
        assertThat(apply(SECRET_KEY, "Loaded {token: MTIzNDU2, guild: 1}"))
            .isEqualTo("Loaded {token: [redacted], guild: 1}");
        assertThat(apply(SECRET_KEY, "Bad settings (token: MTIzNDU2)")).isEqualTo("Bad settings (token: [redacted])");
        assertThat(apply(SECRET_KEY, "discord: token: MTIzNDU2")).isEqualTo("discord: token: [redacted]");
        assertThat(apply(SECRET_KEY, "config.yml: token: abc123")).isEqualTo("config.yml: token: [redacted]");
        assertThat(apply(SECRET_KEY, "[12:00:01 INFO]: token: abc123")).isEqualTo("[12:00:01 INFO]: token: [redacted]");
        assertThat(apply(SECRET_KEY, "[Shop] secrets: abc123 and [Shop]\t  Secret: abc123"))
            .isEqualTo("[Shop] secrets: [redacted] and [Shop]\t  Secret: [redacted]");
    }

    @Test
    void thePlainWordSecretOrTokenAfterAWordIsAKeyWhenItsValueHoldsADigitInSixOrMoreCharacters()
        throws ReflectiveOperationException {
        assertThat(apply(SECRET_KEY, "Bot token: abc123")).isEqualTo("Bot token: [redacted]");
        assertThat(apply(SECRET_KEY, "Invalid bot token: MTIzNDU2.abc.def given"))
            .isEqualTo("Invalid bot token: [redacted] given");
        assertThat(apply(SECRET_KEY, "the secret: \"s3cr3t-value\" was refused"))
            .isEqualTo("the secret: [redacted] was refused");
        assertThat(apply(SECRET_KEY, "Wrong tokens:   abcdef7")).isEqualTo("Wrong tokens:   [redacted]");
        assertThat(apply(SECRET_KEY, "Bot token: abc12")).isEqualTo("Bot token: abc12");
        assertThat(apply(SECRET_KEY, "Bot token: abcdefgh")).isEqualTo("Bot token: abcdefgh");
        assertThat(sent("Bot token: MTIzNDU2")).doesNotContain("MTIzNDU2");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[Shop] Discord login failed, token: MTIzNDU2.abc.def",
        "Loaded {token: MTIzNDU2, guild: 1}",
        "Bad settings (token: MTIzNDU2)",
        "discord: token: MTIzNDU2",
        "config.yml: token: MTIzNDU2",
        "[12:00:01 INFO]: token: MTIzNDU2",
    })
    void aTrackerWithTheRulesSendsNoTokenThatFollowsAKeyInsideALine(String message)
        throws ReflectiveOperationException {
        assertThat(sent(message)).doesNotContain("MTIzNDU2");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Invalid token: expected ;",
        "Not enough tokens: 5 required",
        "Unexpected token: } at line 3",
        "Could not read the secret: file missing",
        "Known secrets: none",
    })
    void thePlainWordSecretOrTokenBeforeAColonInASentenceIsLeftAsWritten(String line) throws Exception {
        assertThat(apply(SECRET_KEY, line)).isEqualTo(line);
        assertThat(Anonymizers.anonymize(line)).isEqualTo(line);
        assertThat(sent(line)).isEqualTo(line);
    }

    @Test
    void aPasswordBeforeAColonInASentenceIsRedacted() {
        assertThat(apply(SECRET_KEY, "Login failed with password: hunter2"))
            .isEqualTo("Login failed with password: [redacted]");
        assertThat(apply(SECRET_KEY, "Wrong pwd: hunter2 or api-key: k-1"))
            .isEqualTo("Wrong pwd: [redacted] or api-key: [redacted]");
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
    void aSentenceThatHoldsTheStartOfAStatementIsHiddenFromThatWordOn() {
        assertThat(apply(SQL, "Please select a kit from the list")).isEqualTo("Please [sql hidden]");
        assertThat(apply(SQL, "Could not delete from disk cache")).isEqualTo("Could not [sql hidden]");
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
    void aWindowsPathWithASpaceInAFolderNameIsHiddenWhole() {
        assertThat(apply(WINDOWS_PATH, "Cannot read C:\\Users\\John Smith\\Desktop\\x.yml"))
            .isEqualTo("Cannot read [path hidden]");
        assertThat(apply(WINDOWS_PATH, "Cannot read C:\\Program Files (x86)\\My Mc Server\\x.yml now"))
            .isEqualTo("Cannot read [path hidden] now");
        assertThat(Anonymizers.anonymize("Cannot read C:\\Users\\John Smith\\Desktop\\x.yml"))
            .isEqualTo("Cannot read [path hidden]");
    }

    @Test
    void aSpaceInTheLastNameOfAWindowsPathEndsIt() {
        assertThat(apply(WINDOWS_PATH, "copy C:\\a\\b to D:\\c\\d failed"))
            .isEqualTo("copy [path hidden] to [path hidden] failed");
        assertThat(apply(WINDOWS_PATH, "Cannot read C:\\Users\\John\\My File.yml"))
            .isEqualTo("Cannot read [path hidden] File.yml");
    }

    @Test
    void theWordsAfterAFileNameOfAWindowsPathAreLeftAsWritten() {
        assertThat(apply(WINDOWS_PATH, "C:\\srv\\x.yml is not valid/readable"))
            .isEqualTo("[path hidden] is not valid/readable");
        assertThat(apply(WINDOWS_PATH, "Loaded C:\\srv\\x.yml in 3 ms/tick budget"))
            .isEqualTo("Loaded [path hidden] in 3 ms/tick budget");
        assertThat(apply(WINDOWS_PATH, "Saved C:\\srv\\x.yml (see docs/config for the format)"))
            .isEqualTo("Saved [path hidden] (see docs/config for the format)");
        assertThat(apply(WINDOWS_PATH, "Cannot move C:\\srv\\a.yml to backup/b.yml now"))
            .isEqualTo("Cannot move [path hidden] to backup/b.yml now");
        assertThat(apply(WINDOWS_PATH, "Cannot move C:/My Server/Shop.jar to old/b now"))
            .isEqualTo("Cannot move [path hidden] to old/b now");
    }

    @Test
    void aFolderNameWithADotOrAVersionKeepsItsSpaces() {
        assertThat(apply(WINDOWS_PATH, "Cannot read C:\\Users\\J. Smith\\Paper 1.21.4 Server\\x.yml now"))
            .isEqualTo("Cannot read [path hidden] now");
        assertThat(apply(WINDOWS_PATH, "Opened C:\\srv\\data and logs/x now")).isEqualTo("Opened [path hidden] now");
    }

    @Test
    void aWindowsPathWrittenWithForwardSlashesIsHidden() {
        assertThat(apply(WINDOWS_PATH, "Cannot read C:/Users/John/Desktop/x.yml"))
            .isEqualTo("Cannot read [path hidden]");
        assertThat(apply(WINDOWS_PATH, "Cannot read d:/Users/John Smith/Desktop\\x.yml now"))
            .isEqualTo("Cannot read [path hidden] now");
        assertThat(Anonymizers.anonymize("Cannot read C:/Users/John/Desktop/x.yml"))
            .isEqualTo("Cannot read [path hidden]");
    }

    @Test
    void aColonAndASlashThatStartNoWindowsPathAreLeftAsWritten() {
        assertThat(apply(WINDOWS_PATH, "GET https://example.org/a/b and redis://cache:6379/0"))
            .isEqualTo("GET https://example.org/a/b and redis://cache:6379/0");
        assertThat(apply(WINDOWS_PATH, "x://a/b and key minecraft:/stone and 1:/2"))
            .isEqualTo("x://a/b and key minecraft:/stone and 1:/2");
        assertThat(apply(WINDOWS_PATH, "Could not delete file: missing")).isEqualTo("Could not delete file: missing");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Read profile:/home/x done",
        "Wrote logfile:/var/log/mc/latest.log",
    })
    void theWordFileAtTheEndOfALongerWordStartsNoFileUrl(String line) {
        assertThat(apply(WINDOWS_PATH, line)).isEqualTo(line);
        assertThat(Anonymizers.anonymize(line)).isEqualTo(line);
    }

    @Test
    void aFileUrlIsHidden() {
        assertThat(apply(WINDOWS_PATH, "Cannot read file:///C:/Users/John/x.yml"))
            .isEqualTo("Cannot read [path hidden]");
        assertThat(apply(WINDOWS_PATH, "Cannot read file:///home/john/x.yml now"))
            .isEqualTo("Cannot read [path hidden] now");
        assertThat(apply(WINDOWS_PATH, "in jar:FILE:/C:/srv/plugins/Shop.jar!/plugin.yml and 'file:/home/john/x'"))
            .isEqualTo("in jar:[path hidden] and '[path hidden]'");
        assertThat(Anonymizers.anonymize("Cannot read file:///C:/Users/John/x.yml"))
            .isEqualTo("Cannot read [path hidden]");
        assertThat(Anonymizers.anonymize("Cannot read file:///home/john/x.yml"))
            .isEqualTo("Cannot read [path hidden]");
    }

    @Test
    void anAbsoluteUnixPathIsHidden() {
        assertThat(apply(UNIX_PATH, "cannot read /home/mc/server/plugins/Shop/data.db now"))
            .isEqualTo("cannot read [path hidden] now");
        assertThat(apply(UNIX_PATH, "open(/etc/passwd) and '/var/lib/mysql/'"))
            .isEqualTo("open([path hidden]) and '[path hidden]'");
    }

    @Test
    void oneSegmentIsHiddenOnlyWithAFurtherSlash() {
        assertThat(apply(UNIX_PATH, "open /home/ failed")).isEqualTo("open [path hidden] failed");
        assertThat(apply(UNIX_PATH, "open /home failed")).isEqualTo("open /home failed");
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
    void theIdAndTokenOfADiscordWebhookUrlAreHidden() {
        String path = "webhooks/" + WEBHOOK_ID + "/" + WEBHOOK_TOKEN;

        assertThat(apply(DISCORD_WEBHOOK, "404 for URL: https://discord.com/api/" + path))
            .isEqualTo("404 for URL: https://discord.com/api/webhooks/[id hidden]/[token hidden]");
        assertThat(apply(DISCORD_WEBHOOK, "POST https://discordapp.com/api/v10/" + path + "?wait=true failed"))
            .isEqualTo("POST https://discordapp.com/api/v10/webhooks/[id hidden]/[token hidden] failed");
        assertThat(apply(DISCORD_WEBHOOK, "POST https://CANARY.discord.com/api/" + path + " failed"))
            .isEqualTo("POST https://CANARY.discord.com/api/webhooks/[id hidden]/[token hidden] failed");
        assertThat(apply(DISCORD_WEBHOOK, "POST https://ptb.discord.com/api/" + path + "/slack failed"))
            .isEqualTo("POST https://ptb.discord.com/api/webhooks/[id hidden]/[token hidden]/slack failed");
    }

    @Test
    void aUrlThatIsNoDiscordWebhookIsLeftAsWrittenByTheWebhookRule() {
        assertThat(apply(DISCORD_WEBHOOK, "GET https://discord.com/api/v10/channels/123456789012345678 failed"))
            .isEqualTo("GET https://discord.com/api/v10/channels/123456789012345678 failed");
        assertThat(apply(DISCORD_WEBHOOK, "POST https://example.org/api/webhooks/12/abc failed"))
            .isEqualTo("POST https://example.org/api/webhooks/12/abc failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Server returned HTTP response code: 404 for URL: https://discord.com/api/webhooks/%s/%s",
        "POST https://discordapp.com/api/v10/webhooks/%s/%s?wait=true failed",
        "POST https://canary.discord.com/api/webhooks/%s/%s failed",
        "POST https://ptb.discord.com/api/webhooks/%s/%s/slack failed",
    })
    void aTrackerWithTheRulesSendsNoDiscordWebhookIdOrToken(String format) throws ReflectiveOperationException {
        assertThat(sent(format.formatted(WEBHOOK_ID, WEBHOOK_TOKEN)))
            .doesNotContain(WEBHOOK_ID)
            .doesNotContain(WEBHOOK_TOKEN)
            .contains("webhooks/[id hidden]/[token hidden]");
    }

    @Test
    void theRulesTogetherHideTheHostIdAndTokenOfADiscordWebhookUrl() {
        String path = "webhooks/" + WEBHOOK_ID + "/" + WEBHOOK_TOKEN;

        assertThat(Anonymizers.anonymize("404 for URL: https://discord.com/api/" + path))
            .isEqualTo("404 for URL: https://[host hidden]/api/webhooks/[id hidden]/[token hidden]");
        assertThat(Anonymizers.anonymize("POST https://discordapp.com/api/v10/" + path + "?wait=true failed"))
            .isEqualTo("POST https://[host hidden]/api/v10/webhooks/[id hidden]/[token hidden] failed");
        assertThat(Anonymizers.anonymize("POST https://canary.discord.com/api/" + path + " failed"))
            .isEqualTo("POST https://[host hidden]/api/webhooks/[id hidden]/[token hidden] failed");
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
    void aDottedNameWhoseLastLabelIsAHostEndingIsHidden() {
        assertThat(apply(HOST, "file messages.de missing")).isEqualTo("file [host hidden] missing");
        assertThat(apply(HOST, "ping example.io, mc.example.gg and javadoc.io"))
            .isEqualTo("ping [host hidden], [host hidden] and [host hidden]");
        assertThat(apply(HOST, "ping abc.br and ab.internal")).isEqualTo("ping [host hidden] and [host hidden]");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Caused by java.io",
        "Unknown language pt.br in config",
        "Could not load class com.example.Me",
        "package javax.net does not exist",
        "module jdk.net, sun.net, kotlin.io, org.example.internal and net.example.local.",
        "locales EN.US, fr.ca. and de.de",
    })
    void aPackageRootOrATwoLetterPairIsLeftAsWritten(String line) throws Exception {
        assertThat(apply(HOST, line)).isEqualTo(line);
        assertThat(Anonymizers.anonymize(line)).isEqualTo(line);
        assertThat(sent(line)).isEqualTo(line);
    }

    @Test
    void theQuotedNameAfterDatabaseIsHidden() {
        assertThat(apply(DATABASE, "FATAL: no pg_hba.conf entry for host \"10.0.0.5\", user \"mc_prod\","
            + " database \"survival_prod\", no encryption"))
            .isEqualTo("FATAL: no pg_hba.conf entry for host \"10.0.0.5\", user \"mc_prod\","
                + " database [database hidden], no encryption");
        assertThat(apply(DATABASE, "FATAL: DATABASE \"survival_prod\" does not exist"))
            .isEqualTo("FATAL: DATABASE [database hidden] does not exist");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "the database is locked",
        "database \"\" is empty",
        "the database \"went away\" again",
        "databases \"a\"",
    })
    void theWordDatabaseWithoutAQuotedNameIsLeftAsWritten(String line) {
        assertThat(apply(DATABASE, line)).isEqualTo(line);
    }

    @Test
    void thePgHbaMessageKeepsItsWordsUnderEveryRule() throws ReflectiveOperationException {
        String entry = "FATAL: no pg_hba.conf entry for host \"db.myserver.host\", user \"mc_prod\","
            + " database \"survival_prod\", no encryption";
        String hidden = "FATAL: no pg_hba.conf entry for host \"[host hidden]\", user [user hidden],"
            + " database [database hidden], no encryption";

        assertThat(Anonymizers.anonymize(entry)).isEqualTo(hidden);
        assertThat(sent(entry)).isEqualTo(hidden);
    }

    @Test
    void theHostOfAUriIsHiddenWithAnyEndingAndAsOneLabel() {
        assertThat(apply(URI_HOST, "redis://cache:6379 refused")).isEqualTo("redis://[host hidden]:6379 refused");
        assertThat(apply(URI_HOST, "GET HTTPS://db.myserver.host/v1/items failed"))
            .isEqualTo("GET HTTPS://[host hidden]/v1/items failed");
        assertThat(apply(URI_HOST, "redis://[credentials hidden]@cache:6379 refused"))
            .isEqualTo("redis://[credentials hidden]@[host hidden]:6379 refused");
        assertThat(apply(URI_HOST, "mongodb://app@db-1.myserver.host/game"))
            .isEqualTo("mongodb://[host hidden]/game");
    }

    @Test
    void localhostAndAUriWithoutAHostAreLeftAsWrittenByTheUriHostRule() {
        assertThat(apply(URI_HOST, "GET http://localhost:8080/users failed and http://LOCALHOST/"))
            .isEqualTo("GET http://localhost:8080/users failed and http://LOCALHOST/");
        assertThat(apply(URI_HOST, "GET http://localhost.myserver.host/ failed"))
            .isEqualTo("GET http://[host hidden]/ failed");
        assertThat(apply(URI_HOST, "http://[host hidden]/a and http://[::1]:80 and a://"))
            .isEqualTo("http://[host hidden]/a and http://[::1]:80 and a://");
    }

    @Test
    void aDottedNameAfterAConnectionWordIsHiddenWithAnyEnding() {
        assertThat(apply(NAMED_HOST, "Connection to db.myserver.host refused"))
            .isEqualTo("Connection to [host hidden] refused");
        assertThat(apply(NAMED_HOST, "Connection to db.myserver.host:5432 refused."))
            .isEqualTo("Connection to [host hidden]:5432 refused.");
        assertThat(apply(NAMED_HOST, "java.net.UnknownHostException: db.myserver.host: Name or service not known"))
            .isEqualTo("java.net.UnknownHostException: [host hidden]: Name or service not known");
        assertThat(apply(NAMED_HOST, "java.net.UnknownHostException: No such host is known (db.myserver.host)"))
            .isEqualTo("java.net.UnknownHostException: No such host is known ([host hidden])");
        assertThat(apply(NAMED_HOST, "Could not connect to play.myserver.host. No route to mc-1.myserver.zone"))
            .isEqualTo("Could not connect to [host hidden]. No route to [host hidden]");
        assertThat(apply(NAMED_HOST, "Failed connecting to 'db.myserver.host' while connected to a.b"))
            .isEqualTo("Failed connecting to '[host hidden]' while connected to [host hidden]");
        assertThat(Anonymizers.anonymize("Connection to db.myserver.host refused"))
            .isEqualTo("Connection to [host hidden] refused");
    }

    @Test
    void theHostOfAMariaDbDriverMessageIsHiddenWithAnyEnding() {
        assertThat(Anonymizers.anonymize("Socket fail to connect to address=(host=db.myserver.host)(port=3306)"
            + "(type=primary). db.myserver.host")).isEqualTo("Socket fail to connect to address=(host=[host hidden])"
            + "(port=3306)(type=primary). [host hidden]");
        assertThat(Anonymizers.anonymize("Socket fail to connect to address=(host=db.myserver.host)(port=3306)"
            + "(type=primary). db.myserver.host: Name or service not known"))
            .isEqualTo("Socket fail to connect to address=(host=[host hidden])(port=3306)(type=primary). "
                + "[host hidden]: Name or service not known");
        assertThat(Anonymizers.anonymize("Could not connect to address=(host=db.myserver.host)(port=3306)"
            + "(type=primary) : Connection refused")).isEqualTo("Could not connect to address=(host=[host hidden])"
            + "(port=3306)(type=primary) : Connection refused");
        assertThat(apply(NAMED_HOST, "Socket fail to connect to address=(host=db.example.net). db.example.net"))
            .isEqualTo("Socket fail to connect to address=(host=[host hidden]). [host hidden]");
        assertThat(apply(NAMED_HOST, "Loaded (type=primary). kits.starter is next"))
            .isEqualTo("Loaded (type=primary). kits.starter is next");
    }

    @Test
    void aDottedNameAfterAHostOrAddressKeyIsHiddenWithAnyEnding() {
        assertThat(apply(NAMED_HOST, "No route to host: play.myserver.network"))
            .isEqualTo("No route to host: [host hidden]");
        assertThat(apply(NAMED_HOST, "servers=[{address=db.myserver.host:27017, type=UNKNOWN"))
            .isEqualTo("servers=[{address=[host hidden]:27017, type=UNKNOWN");
        assertThat(apply(NAMED_HOST, "host: \"db.example.zone\" and hostname=db.example.zone"))
            .isEqualTo("host: \"[host hidden]\" and hostname=[host hidden]");
        assertThat(apply(NAMED_HOST, "{\"host\":\"db.example.zone\",\"db_host\" = 'db.example.zone'}"))
            .isEqualTo("{\"host\":\"[host hidden]\",\"db_host\" = '[host hidden]'}");
        assertThat(apply(NAMED_HOST, "serverAddress: db.example.zone")).isEqualTo("serverAddress: [host hidden]");
    }

    @Test
    void aDottedNameAfterRefusedOrTimedOutIsHiddenWithAnyEnding() {
        assertThat(apply(NAMED_HOST, "Connection refused: db.myserver.host/10.0.0.5:6379"))
            .isEqualTo("Connection refused: [host hidden]/10.0.0.5:6379");
        assertThat(apply(NAMED_HOST, "connection timed out: db.myserver.host/10.0.0.5:6379"))
            .isEqualTo("connection timed out: [host hidden]/10.0.0.5:6379");
        assertThat(apply(NAMED_HOST, "connection timed out after 30000 ms: db.myserver.host/10.0.0.5:6379"))
            .isEqualTo("connection timed out after 30000 ms: [host hidden]/10.0.0.5:6379");
        assertThat(apply(NAMED_HOST, "Connection refused: connect")).isEqualTo("Connection refused: connect");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Socket fail to connect to address=(host=db.myserver.host)(port=3306)(type=primary). db.myserver.host",
        "Could not connect to address=(host=db.myserver.host)(port=3306)(type=primary) : Connection refused",
        "No route to host: db.myserver.host",
        "Connection refused: db.myserver.host/10.0.0.5:6379",
        "servers=[{address=db.myserver.host:27017, type=UNKNOWN",
        "host: \"db.myserver.host\"",
        "hostname=db.myserver.host",
    })
    void aTrackerWithTheRulesSendsNoHostThatFollowsAKey(String message) throws ReflectiveOperationException {
        assertThat(sent(message)).doesNotContain("myserver");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "connected to 1.21.8-R0.1-SNAPSHOT",
        "running on host 1.21.4 build",
        "Lost connection to so.alaz.shop.Sync channel",
        "No handler for host me.john.shop.Shop",
        "Failed to connect to MySQL.Check your config",
        "The host player.getName() returned null",
        "Cannot connect to plugin.yml loader",
        "Could not find host config.yml in the folder",
        "Invalid address: data.db is locked",
    })
    void aVersionAClassNameACallOrAFileNameAfterAConnectionWordIsLeftAsWritten(String line) {
        assertThat(apply(NAMED_HOST, line)).isEqualTo(line);
        assertThat(Anonymizers.anonymize(line)).isEqualTo(line);
    }

    @Test
    void anotherDottedNameAfterAConnectionWordIsHidden() {
        assertThat(apply(NAMED_HOST, "No value for host shop.balance")).isEqualTo("No value for host [host hidden]");
        assertThat(apply(NAMED_HOST, "connect to 3.myserver.host or host db.myserver.Sync.host"))
            .isEqualTo("connect to [host hidden] or host [host hidden]");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "connect to cache:6379 or localhost failed",
        "Unknown host cache",
        "write to data.db failed",
        "the ghost a.b and localhost a.b",
        "host  a.b",
        "db.myserver.host is down",
    })
    void aNameThatFollowsNoConnectionWordOrHasOneLabelIsLeftAsWritten(String line) {
        assertThat(apply(NAMED_HOST, line)).isEqualTo(line);
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
        assertThat(Anonymizers.anonymize("redis://default:hunter2@cache:6379 refused"))
            .isEqualTo("redis://[credentials hidden]@[host hidden]:6379 refused");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Cannot read C:\\Users\\John Smith\\Desktop\\x.yml",
        "mail to john.smith@example.online failed",
        "Cannot read C:/Users/John/Desktop/x.yml",
        "Cannot read file:///C:/Users/John/x.yml",
        "Cannot read file:///home/john/x.yml",
        "Connection to db.myserver.host refused",
        "GET https://db.myserver.host/v1 failed",
        "FATAL: no pg_hba.conf entry for host \"10.0.0.5\", user \"mc_prod\", database \"survival_prod\", SSL off",
        "redis://default:hun/ter2@cache:6379 failed",
    })
    void aTrackerWithTheRulesSendsNoneOfThePrivateParts(String message) throws ReflectiveOperationException {
        assertThat(sent(message)).doesNotContain("John", "Smith", "Desktop", "john", "example", "myserver",
            "survival_prod", "mc_prod", "ter2", "hun", "cache");
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
        "Invalid token: expected ;",
        "Not enough tokens: 5 required",
        "Caused by java.io",
        "Unknown language pt.br in config",
        "Could not load class com.example.Me",
        "GET http://localhost:8080/users failed",
        "Error in config.yml:12, see key database.host",
    })
    void anOrdinaryMessageIsLeftAsWrittenByEveryRule(String line) {
        assertThat(Anonymizers.anonymize(line)).isEqualTo(line);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "a", "a.", "a-", "a@", "a.a@", "x@a.", "/a", "/", "://", "a://", "x://a:", "a:", "'", "'a'@", "0", "0-", "f",
        "select ", "select a from ", "update a ", "update a set", "insert  ", "insert or ", "create table ",
        "password", "password=", "password \"", "token:", " ", "user ", "user \"", "user \"a",
        "\t", "\\", "C:\\", ".com", "a.com.", "jdbc:", "Bearer ", "?token=", "\"", "-", ".",
        "x://a:b/", "x://a:/", "x://a@", "a.b@c.", "@a.b", "token", "token ", "'token' ", "a-token", "tokens:",
        "C:/", "C:\\a ", "C:/a a/", "a a ", "file:/", "aa.bb ", "java.", "database \"", "host ", "host a.",
        "connect to a.", "://", "localhost", "host=", "host=a.", "address=(host=a.b)", "(a). ", "refused: a.",
        "jdbc:a:/", "jdbc:a:C:\\a ", "jdbc:a:/a a/", "a.yml ", "C:/a.b a/", ", token", ",        ", "x://a:1,",
        "x://a:1/", "://a:", "a.B", "a.yml", "a.b(",
    })
    void aLongAdversarialTextIsAnonymizedQuickly(String unit) {
        String text = unit.repeat(100_000 / unit.length() + 1);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Anonymizers.anonymize(text);
            Anonymizers.anonymize("x@" + text);
            Anonymizers.anonymize("x://" + text);
            Anonymizers.anonymize("password=" + text);
            Anonymizers.anonymize("/a/" + text);
            Anonymizers.anonymize("x://a:" + text + "@b");
            Anonymizers.anonymize("C:\\" + text);
            Anonymizers.anonymize("C:/" + text);
            Anonymizers.anonymize("host " + text);
            Anonymizers.anonymize("host=" + text);
            Anonymizers.anonymize("jdbc:a:/" + text);
            Anonymizers.anonymize("address=(host=a.b)" + text);
            Anonymizers.anonymize("," + text + "token:");
            Anonymizers.anonymize("token" + text + ":");
        });
    }

    @Test
    void applyAddsTheRulesToTheTrackerItIsGiven() {
        ErrorTracker tracker = ErrorTracker.contextUnaware();

        assertThat(Anonymizers.apply(tracker)).isSameAs(tracker);
        assertThat(tracker.isContextAttached()).isFalse();
    }

    /** The message a tracker carrying the rules sends for an exception with {@code message}. */
    private static String sent(String message) throws ReflectiveOperationException {
        ErrorTracker tracker = Anonymizers.apply(ErrorTracker.contextUnaware());
        tracker.trackError(new RuntimeException(message));
        Method data = tracker.getClass().getDeclaredMethod("getFullData");
        data.setAccessible(true);
        return ((JsonArray) data.invoke(tracker)).get(0).getAsJsonObject().get("message").getAsString();
    }

    private static String apply(int rule, String line) {
        return line.replaceAll(Anonymizers.RULES[rule][0], Anonymizers.RULES[rule][1]);
    }
}
