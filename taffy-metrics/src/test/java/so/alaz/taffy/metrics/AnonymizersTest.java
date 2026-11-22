package so.alaz.taffy.metrics;

import com.google.gson.JsonArray;
import dev.faststats.ErrorTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    private static final int TOKEN = 18;

    private static final String WEBHOOK_ID = "123456789012345678";
    private static final String WEBHOOK_TOKEN = "abcDEF-ghi_JKL";

    @Test
    void thereAreNineteenRulesOfAPatternAndAReplacement() {
        assertThat(Anonymizers.RULES).hasDimensions(19, 2);
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
    void anEmailAddressWithAPlusInItsNameIsHiddenWhole() throws ReflectiveOperationException {
        assertThat(apply(EMAIL, "mail to john+tag@sub.example.co.uk failed"))
            .isEqualTo("mail to [email hidden] failed");
        assertThat(apply(EMAIL, "a+b=c and x+@y")).isEqualTo("a+b=c and x+@y");
        assertThat(Anonymizers.anonymize("mail to john+tag@sub.example.co.uk failed"))
            .isEqualTo("mail to [email hidden] failed");
        assertThat(sent("mail to john+tag@sub.example.co.uk failed")).isEqualTo("mail to [email hidden] failed");
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
    void theCredentialsOfABasicAuthorizationAreHidden() throws ReflectiveOperationException {
        assertThat(apply(BEARER, "Authorization: Basic dXNlcjpodW50ZXIy sent"))
            .isEqualTo("Authorization: Basic [token hidden] sent");
        assertThat(apply(BEARER, "headers {\"authorization\": \"Basic dXNlcjpodW50ZXIy\"}"))
            .isEqualTo("headers {\"authorization\": \"Basic [token hidden]\"}");
        assertThat(apply(BEARER, "Authorization=Basic dXNlcjpodW50ZXIy="))
            .isEqualTo("Authorization=Basic [token hidden]");
        assertThat(Anonymizers.anonymize("Authorization: Basic dXNlcjpodW50ZXIy sent"))
            .isEqualTo("Authorization: Basic [token hidden] sent");
        assertThat(sent("Authorization: Basic dXNlcjpodW50ZXIy sent")).doesNotContain("dXNlcjpodW50ZXIy");
    }

    @Test
    void theCredentialsOfABasicAuthorizationAreHiddenAsJavaPrintsAHeader() throws ReflectiveOperationException {
        String headers = "{Authorization=[Basic dXNlcjpwYXNz], Content-Type=[application/json]}";

        assertThat(apply(BEARER, headers))
            .isEqualTo("{Authorization=[Basic [token hidden]], Content-Type=[application/json]}");
        assertThat(apply(BEARER, "java.net.http.HttpHeaders@1a2b { {authorization=[Basic dXNlcjpwYXNz]} }"))
            .isEqualTo("java.net.http.HttpHeaders@1a2b { {authorization=[Basic [token hidden]]} }");
        assertThat(apply(BEARER, "setHeader(\"Authorization\", \"Basic dXNlcjpwYXNz\")"))
            .isEqualTo("setHeader(\"Authorization\", \"Basic [token hidden]\")");
        assertThat(apply(BEARER, "Authorization:  Basic  dXNlcjpwYXNz and Authorization: Basic YTpi"))
            .isEqualTo("Authorization:  Basic [token hidden] and Authorization: Basic [token hidden]");
        assertThat(sent(headers)).doesNotContain("dXNlcjpwYXNz");
    }

    @Test
    void anOrdinaryWordAfterABasicAuthorizationIsLeftAsWritten() {
        assertThat(apply(BEARER, "Authorization: Basic authentication is not supported"))
            .isEqualTo("Authorization: Basic authentication is not supported");
        assertThat(apply(BEARER, "Authorization: Basic is required")).isEqualTo("Authorization: Basic is required");
        assertThat(apply(BEARER, "Authorization: Basic Auth")).isEqualTo("Authorization: Basic Auth");
    }

    @Test
    void theWordBasicWithoutAnAuthorizationKeyIsLeftAsWritten() {
        assertThat(apply(BEARER, "Basic kit and basic auth failed")).isEqualTo("Basic kit and basic auth failed");
        assertThat(apply(BEARER, "Authorization: failed for Basic kit"))
            .isEqualTo("Authorization: failed for Basic kit");
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
    void theValueOfAPassphraseALicenseKeyOrAPassKeyIsRedacted() throws ReflectiveOperationException {
        assertThat(apply(SECRET_KEY, "Wrong passphrase: hunter2 and license-key: ABCD-1234-EFGH"))
            .isEqualTo("Wrong passphrase: [redacted] and license-key: [redacted]");
        assertThat(apply(SECRET_KEY, "Invalid licenseKey=ABCD-1234 or LICENCE_KEY: x"))
            .isEqualTo("Invalid licenseKey=[redacted] or LICENCE_KEY: [redacted]");
        assertThat(apply(SECRET_KEY, "login with pass=hunter2 and db_pass = hunter2, MYSQL_PASS=hunter2"))
            .isEqualTo("login with pass=[redacted] and db_pass = [redacted], MYSQL_PASS=[redacted]");
        assertThat(apply(SECRET_KEY, "'pass' => 'hunter2' and config: 'password' => 'hunter2'"))
            .isEqualTo("'pass' => [redacted] and config: 'password' => [redacted]");
        assertThat(apply(SECRET_KEY, "password => 'hunter2'")).isEqualTo("password => [redacted]");
        assertThat(apply(SECRET_KEY, "dbPass=hunter2 and pass=1234"))
            .isEqualTo("dbPass=[redacted] and pass=[redacted]");
        assertThat(apply(SECRET_KEY, "{\"pass\": \"hunter2\", 'db_pass': 'hunter2', \"dbPass\": \"hunter2\"}"))
            .isEqualTo("{\"pass\": [redacted], 'db_pass': [redacted], \"dbPass\": [redacted]}");
        assertThat(sent("'pass' => 'hunter2'")).doesNotContain("hunter2");
        assertThat(sent("{\"db_pass\": \"hunter2\"}")).doesNotContain("hunter2");
        assertThat(sent("login with pass=hunter2")).isEqualTo("login with pass=[redacted]");
        assertThat(sent("Wrong passphrase: hunter2")).isEqualTo("Wrong passphrase: [redacted]");
        assertThat(sent("Invalid license-key: ABCD-1234-EFGH")).isEqualTo("Invalid license-key: [redacted]");
    }

    @Test
    void aLongerWordThatEndsInPassOrAPassBeforeAColonIsLeftAsWritten() {
        assertThat(apply(SECRET_KEY, "bypass=true compass=north")).isEqualTo("bypass=true compass=north");
        assertThat(apply(SECRET_KEY, "Battle pass: 3 of 5")).isEqualTo("Battle pass: 3 of 5");
        assertThat(apply(SECRET_KEY, "passenger=Steve")).isEqualTo("passenger=Steve");
        assertThat(apply(SECRET_KEY, "{\"bypass\": \"x\", \"compass\": \"north\"}"))
            .isEqualTo("{\"bypass\": \"x\", \"compass\": \"north\"}");
    }

    @Test
    void aCountOfOneToThreeDigitsAfterAPassKeyIsLeftAsWritten() {
        assertThat(apply(SECRET_KEY, "pass=3 fail=2 skip=0")).isEqualTo("pass=3 fail=2 skip=0");
        assertThat(apply(SECRET_KEY, "tests: pass=12, fail=0")).isEqualTo("tests: pass=12, fail=0");
        assertThat(apply(SECRET_KEY, "{\"pass\": 120, \"fail\": 2}")).isEqualTo("{\"pass\": 120, \"fail\": 2}");
        assertThat(apply(SECRET_KEY, "pass=12ab")).isEqualTo("pass=[redacted]");
    }

    @Test
    void aPasswordThatStartsItsLineIsRedactedToTheEndOfTheLine() throws ReflectiveOperationException {
        assertThat(apply(SECRET_KEY, "password: my pass word")).isEqualTo("password: [redacted]");
        assertThat(apply(SECRET_KEY, "while parsing a block mapping\n  db-password: my pass word\n  port: 3306"))
            .isEqualTo("while parsing a block mapping\n  db-password: [redacted]\n  port: 3306");
        assertThat(apply(SECRET_KEY, "password=my pass word")).isEqualTo("password=[redacted]");
        assertThat(apply(SECRET_KEY, "- passphrase: correct horse battery staple"))
            .isEqualTo("- passphrase: [redacted]");
        assertThat(apply(SECRET_KEY, "PWD = my pass word\npasswd:my pass word"))
            .isEqualTo("PWD = [redacted]\npasswd:[redacted]");
        assertThat(apply(SECRET_KEY, "spring.datasource.hikari.password: my pass word"))
            .isEqualTo("spring.datasource.hikari.password: [redacted]");
        assertThat(apply(SECRET_KEY, "some.very.long.prefix.key.db.password = my pass word"))
            .isEqualTo("some.very.long.prefix.key.db.password = [redacted]");
        assertThat(sent("password: my pass word")).isEqualTo("password: [redacted]");
        assertThat(sent("spring.datasource.hikari.password: my pass word")).doesNotContain("pass word");
    }

    @Test
    void aQuotedPasswordOrAYesThatStartsItsLineIsReadAsBefore() {
        assertThat(apply(SECRET_KEY, "password: \"my pass\" word")).isEqualTo("password: [redacted] word");
        assertThat(apply(SECRET_KEY, "password: YES")).isEqualTo("password: YES");
        assertThat(apply(SECRET_KEY, "password:   no")).isEqualTo("password:   no");
        assertThat(apply(SECRET_KEY, "tokenValue: abc123 rejected")).isEqualTo("tokenValue: [redacted] rejected");
    }

    @Test
    void aPasswordInsideALineIsRedactedForOneWord() {
        assertThat(apply(SECRET_KEY, "Login failed with password: my pass word"))
            .isEqualTo("Login failed with password: [redacted] pass word");
    }

    @Test
    void theListItemsBelowASecretKeyAreRedactedAsOneValue() throws ReflectiveOperationException {
        assertThat(apply(SECRET_KEY, "while parsing\ntokens:\n  - abc123\n  - def456\nport: 25565"))
            .isEqualTo("while parsing\ntokens:\n  [redacted]\nport: 25565");
        assertThat(apply(SECRET_KEY, "tokens:\n  - abc123")).isEqualTo("tokens:\n  [redacted]");
        assertThat(apply(SECRET_KEY, "secrets:\n- \"abc\" extra\nnext: 1")).isEqualTo("secrets:\n[redacted]\nnext: 1");
        assertThat(apply(SECRET_KEY, "api-key:\n  - k-1\n  - k-2")).isEqualTo("api-key:\n  [redacted]");
        assertThat(apply(SECRET_KEY, "tokens: - abc123")).isEqualTo("tokens: [redacted]");
        assertThat(sent("tokens:\n  - abc123\n  - def456")).doesNotContain("abc123", "def456");
    }

    @Test
    void aBlankOrACommentLineBetweenTheListItemsBelowASecretKeyKeepsThemOneValue()
        throws ReflectiveOperationException {
        assertThat(apply(SECRET_KEY, "tokens:\n  - abc123\n\n  - def456")).isEqualTo("tokens:\n  [redacted]");
        assertThat(apply(SECRET_KEY, "tokens:\n  - abc123\n  # comment\n\n  - def456\nport: 25565"))
            .isEqualTo("tokens:\n  [redacted]\nport: 25565");
        assertThat(apply(SECRET_KEY, "tokens:\r\n  - abc123\r\n\r\n  - def456\r\nport: 25565"))
            .isEqualTo("tokens:\r\n  [redacted]\nport: 25565");
        assertThat(apply(SECRET_KEY, "tokens:\n  - abc123\n\n# comment\nport: 25565"))
            .isEqualTo("tokens:\n  [redacted]\n\n# comment\nport: 25565");
        assertThat(sent("tokens:\n  - abc123\n\n  - def456")).doesNotContain("abc123", "def456");
        assertThat(sent("tokens:\n  - abc123\n  # comment\n  - def456")).doesNotContain("abc123", "def456");
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
        assertThat(apply(SQL, "Could not delete from disk cache")).isEqualTo("Could not [sql hidden]");
        assertThat(apply(SQL, "Please select a kit from kits")).isEqualTo("Please [sql hidden]");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Please select a kit from the list",
        "Please select a kit from the list.",
        "select one of the three kits from the list of 5",
        "Select an item from your inventory, then run /kit",
        "Could not delete from the cache",
        "Failed to insert into the database: timeout",
        "Cannot merge into this stack or replace into that slot",
        "Could not delete from the cache (3 entries left)",
        "Could not delete from the cache: key=shop-12",
        "Failed to insert into the database, it's read only",
    })
    void aSentenceWithADeterminerAfterFromOrIntoIsLeftAsWritten(String line) throws ReflectiveOperationException {
        assertThat(apply(SQL, line)).isEqualTo(line);
        assertThat(Anonymizers.anonymize(line)).isEqualTo(line);
        assertThat(sent(line)).isEqualTo(line);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM the list",
        "SELECT name, uuid FROM the WHERE id = 5",
        "SELECT name FROM their WHERE id = 5",
        "select a kit from the_table where pin = 1234",
        "select a kit from the.players where pin = 1234",
        "select a kit from the, players where pin = 1234",
        "INSERT INTO the (name, pin) VALUES ('John', 1234)",
        "insert into the VALUES ('John', 1234)",
        "DELETE FROM the WHERE name = 'John'",
        "delete from their;",
        "SELECT name FROM the t WHERE pin = 1234",
        "SELECT name FROM that AS t WHERE t.pin = 1234",
        "SELECT pin FROM those ORDER BY pin LIMIT 1",
        "SELECT pin FROM those LIMIT 1",
        "SELECT name FROM my JOIN x ON a = b",
        "SELECT name FROM my\"players\" WHERE pin = 1234",
        "SELECT name FROM the[players] WHERE pin = 1234",
        "SELECT name FROM the-players",
        "SELECT name FROM the 'players'",
        "SELECT name FROM the/**/WHERE pin = 1234",
        "SELECT name FROM the t /* players */",
        "SELECT pin\tFROM\tthe t WHERE name='John'",
        "SELECT name FROM the t GROUP BY name HAVING x",
        "DELETE FROM my USING x WHERE x.id = my.id AND x.name = 'John'",
        "DELETE FROM those RETURNING pin",
        "INSERT INTO my SET name = 'John', pin = 1234",
        "INSERT INTO this DEFAULT VALUES",
        "INSERT OR REPLACE INTO my t VALUES ('John')",
        "REPLACE INTO the SET pin = 1234",
        "MERGE INTO the USING src ON (a = b) WHEN MATCHED THEN UPDATE SET pin = 1234",
    })
    void aStatementWithADeterminerAsItsTableIsHidden(String statement) {
        assertThat(apply(SQL, "failed: " + statement + "\nnext")).isEqualTo("failed: [sql hidden]\nnext");
    }

    @Test
    void aStatementWithADeterminerAsItsTableIsHiddenWhenItsClauseIsOnALaterLine() {
        assertThat(apply(SQL, "SELECT name\nFROM the t\nWHERE pin = 1234")).isEqualTo("[sql hidden]\nWHERE pin = 1234");
        assertThat(apply(SQL, "SELECT name FROM the -- comment\nWHERE pin = 1234"))
            .isEqualTo("[sql hidden]\nWHERE pin = 1234");
        assertThat(apply(SQL, "DELETE FROM the t\nWHERE pin = 1234")).isEqualTo("[sql hidden]\nWHERE pin = 1234");
    }

    @Test
    void aStatementWithADeterminerAsItsTableAndNoClauseIsLeftAsWritten() {
        assertThat(apply(SQL, "SELECT name FROM the t")).isEqualTo("SELECT name FROM the t");
        assertThat(apply(SQL, "DELETE FROM my USING x")).isEqualTo("DELETE FROM my USING x");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT name FROM players WHERE name = 'John'",
        "select p.name from players p where p.pin = 1234",
        "select name from players p",
        "SELECT name FROM players",
        "SELECT COUNT(*) FROM players",
        "select name from players, bans where players.pin = 1234",
        "SELECT name FROM players ORDER BY name LIMIT 5",
        "INSERT INTO players (name, pin) VALUES ('John', 1234)",
        "UPDATE players SET name = 'John' WHERE pin = 1234",
        "DELETE FROM players WHERE name = 'John'",
    })
    void aStatementOfADriverMessageIsHiddenWithItsValues(String statement) throws ReflectiveOperationException {
        String message = "Table \"PLAYERS\" not found; SQL statement:\n" + statement + " [42102-224]";

        assertThat(apply(SQL, message)).isEqualTo("Table \"PLAYERS\" not found; SQL statement:\n[sql hidden]");
        assertThat(sent(message)).doesNotContain("John", "1234", "pin");
    }

    @Test
    void aStatementAfterASentenceOfTheSameLineIsHidden() {
        assertThat(apply(SQL, "select a kit from the list or SELECT x FROM y WHERE z = 1"))
            .isEqualTo("select a kit from the list or [sql hidden]");
        assertThat(apply(SQL, "merge into the main branch failed; DELETE FROM balances WHERE shop = 12"))
            .isEqualTo("merge into the main branch failed; [sql hidden]");
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

    @Test
    void aUncPathIsHidden() throws ReflectiveOperationException {
        assertThat(apply(WINDOWS_PATH, "Cannot read \\\\fileserver\\share\\Users\\john\\x.yml now"))
            .isEqualTo("Cannot read [path hidden] now");
        assertThat(apply(WINDOWS_PATH, "Cannot read \\\\fileserver\\Mc Share\\John Smith\\x.yml now"))
            .isEqualTo("Cannot read [path hidden] now");
        assertThat(apply(WINDOWS_PATH, "Cannot read '\\\\fileserver\\share'")).isEqualTo("Cannot read '[path hidden]'");
        assertThat(Anonymizers.anonymize("Cannot read \\\\fileserver\\share\\Users\\john\\x.yml now"))
            .isEqualTo("Cannot read [path hidden] now");
        assertThat(sent("Cannot read \\\\fileserver\\Mc Share\\John Smith\\x.yml now"))
            .isEqualTo("Cannot read [path hidden] now");
    }

    @Test
    void twoBackslashesWithoutAServerNameAndAFurtherBackslashAreLeftAsWritten() {
        assertThat(apply(WINDOWS_PATH, "Illegal escape \\\\d in pattern and a\\\\b"))
            .isEqualTo("Illegal escape \\\\d in pattern and a\\\\b");
        assertThat(apply(WINDOWS_PATH, "regex \\\\d+\\\\s is invalid")).isEqualTo("regex \\\\d+\\\\s is invalid");
    }

    @Test
    void aRelativeWindowsPathIsHiddenFromItsUsersFolderOn() throws ReflectiveOperationException {
        assertThat(apply(WINDOWS_PATH, "Cannot read Users\\John\\Desktop\\x.yml now"))
            .isEqualTo("Cannot read [path hidden] now");
        assertThat(apply(WINDOWS_PATH, "Cannot read ..\\..\\Users\\John Smith\\Desktop\\x.yml now"))
            .isEqualTo("Cannot read ..\\..\\[path hidden] now");
        assertThat(apply(WINDOWS_PATH, "Cannot read \\users\\john\\x.yml and plugins\\Shop\\data.db"))
            .isEqualTo("Cannot read \\[path hidden] and plugins\\Shop\\data.db");
        assertThat(Anonymizers.anonymize("Cannot read ..\\..\\Users\\John Smith\\Desktop\\x.yml now"))
            .isEqualTo("Cannot read ..\\..\\[path hidden] now");
        assertThat(sent("Cannot read ..\\..\\Users\\John Smith\\Desktop\\x.yml now"))
            .isEqualTo("Cannot read ..\\..\\[path hidden] now");
    }

    @Test
    void theWordUsersWithoutABackslashOrInsideALongerWordStartsNoPath() {
        assertThat(apply(WINDOWS_PATH, "3 Users of a\\b and powerusers\\x"))
            .isEqualTo("3 Users of a\\b and powerusers\\x");
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
    void aUnixPathWithASpaceInAFolderNameIsHiddenWhole() throws ReflectiveOperationException {
        assertThat(apply(UNIX_PATH, "cannot read /home/john smith/server/x now"))
            .isEqualTo("cannot read [path hidden] now");
        assertThat(apply(UNIX_PATH, "Cannot read /srv/My Mc Server/plugins/Shop/config.yml"))
            .isEqualTo("Cannot read [path hidden]");
        assertThat(apply(UNIX_PATH, "Cannot read /Users/John Smith/Library/Application Support/x.dat"))
            .isEqualTo("Cannot read [path hidden]");
        assertThat(Anonymizers.anonymize("cannot read /home/john smith/server/x now"))
            .isEqualTo("cannot read [path hidden] now");
        assertThat(sent("cannot read /home/john smith/server/x now")).isEqualTo("cannot read [path hidden] now");
    }

    @Test
    void aSpaceEndsAUnixPathAsItEndsAWindowsPath() {
        assertThat(apply(UNIX_PATH, "cannot read /home/mc/x.yml is not valid/readable"))
            .isEqualTo("cannot read [path hidden] is not valid/readable");
        assertThat(apply(UNIX_PATH, "copy /a/b to /c/d failed"))
            .isEqualTo("copy [path hidden] to [path hidden] failed");
        assertThat(apply(UNIX_PATH, "cannot read /home/john smith")).isEqualTo("cannot read [path hidden] smith");
        assertThat(apply(UNIX_PATH, "Saved /srv/data and logs/x now")).isEqualTo("Saved [path hidden] now");
        assertThat(apply(UNIX_PATH, "run /shop open first and/or close"))
            .isEqualTo("run /shop open first and/or close");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Failed to save /srv/world after 3 attempts/retries | Failed to save [path hidden] after 3 attempts/retries",
        "World /srv/mc/world uses 12 chunks/tick | World [path hidden] uses 12 chunks/tick",
        "Cannot load /plugins/Shop/lang at line 3/40 | Cannot load [path hidden] at line 3/40",
        "Directory /srv/mc/plugins/Shop is read/write protected | Directory [path hidden] is read/write protected",
        "Loaded /srv/mc/plugins in 5 ms/plugin | Loaded [path hidden] in 5 ms/plugin",
        "Moved /srv/world to backup/world ok | Moved [path hidden] to backup/world ok",
        "Read /srv/world from cache/disk | Read [path hidden] from cache/disk",
        "Path /srv/world was not found/readable | Path [path hidden] was not found/readable",
    })
    void aSentenceWordAfterAUnixPathEndsIt(String line, String sent) throws ReflectiveOperationException {
        assertThat(apply(UNIX_PATH, line)).isEqualTo(sent);
        assertThat(sent(line)).isEqualTo(sent);
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
    void theIdAndTokenOfADiscordWebhookUrlWithoutTlsAreHidden() throws ReflectiveOperationException {
        String path = "webhooks/" + WEBHOOK_ID + "/" + WEBHOOK_TOKEN;

        assertThat(apply(DISCORD_WEBHOOK, "POST http://discord.com/api/" + path + " failed"))
            .isEqualTo("POST http://discord.com/api/webhooks/[id hidden]/[token hidden] failed");
        assertThat(apply(DISCORD_WEBHOOK, "POST HTTP://canary.discordapp.com/api/v10/" + path + "?wait=true failed"))
            .isEqualTo("POST HTTP://canary.discordapp.com/api/v10/webhooks/[id hidden]/[token hidden] failed");
        assertThat(Anonymizers.anonymize("POST http://discord.com/api/" + path + " failed"))
            .isEqualTo("POST http://[host hidden]/api/webhooks/[id hidden]/[token hidden] failed");
        assertThat(sent("POST http://discord.com/api/" + path + " failed"))
            .isEqualTo("POST http://[host hidden]/api/webhooks/[id hidden]/[token hidden] failed");
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

    @Test
    void theSingleQuotedNameAfterDatabaseIsHidden() throws ReflectiveOperationException {
        assertThat(apply(DATABASE, "Unknown database 'survival_prod'")).isEqualTo("Unknown database [database hidden]");
        assertThat(apply(DATABASE, "the database 'went away' again and database ''"))
            .isEqualTo("the database 'went away' again and database ''");
        assertThat(Anonymizers.anonymize("Access denied for user 'mc_prod'@'%' to database 'survival_prod'"))
            .isEqualTo("Access denied for user [user hidden]@[host hidden] to database [database hidden]");
        assertThat(sent("Unknown database 'survival_prod'")).isEqualTo("Unknown database [database hidden]");
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
    void aBareTokenIsHidden() throws ReflectiveOperationException {
        String github = "ghp_" + "a1B2".repeat(9);
        String slack = "xoxb-123456789012-1234567890123-" + "a1B2".repeat(6);
        String stripe = "sk_live_" + "a1B2".repeat(6);

        assertThat(apply(TOKEN, "push failed for " + github + " now")).isEqualTo("push failed for [token hidden] now");
        assertThat(apply(TOKEN, "Slack said " + slack + " is invalid"))
            .isEqualTo("Slack said [token hidden] is invalid");
        assertThat(apply(TOKEN, "Stripe key '" + stripe + "' rejected"))
            .isEqualTo("Stripe key '[token hidden]' rejected");
        assertThat(apply(TOKEN, "github_pat_" + "a1B2_".repeat(6) + " and gho_" + "a1B2".repeat(9)))
            .isEqualTo("[token hidden] and [token hidden]");
        assertThat(Anonymizers.anonymize("push failed for " + github + " now"))
            .isEqualTo("push failed for [token hidden] now");
        assertThat(Anonymizers.anonymize("Slack said " + slack + " is invalid"))
            .isEqualTo("Slack said [token hidden] is invalid");
        assertThat(Anonymizers.anonymize("Stripe key '" + stripe + "' rejected"))
            .isEqualTo("Stripe key '[token hidden]' rejected");
        assertThat(sent(github + " " + slack + " " + stripe)).isEqualTo("[token hidden] [token hidden] [token hidden]");
    }

    @Test
    void aJsonWebTokenIsHidden() throws ReflectiveOperationException {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

        assertThat(apply(TOKEN, "bad jwt " + jwt + " here")).isEqualTo("bad jwt [token hidden] here");
        assertThat(apply(TOKEN, "bad jwt eyJhbGciOiJub25lIn0.eyJzdWIiOiJhIn0. here"))
            .isEqualTo("bad jwt [token hidden] here");
        assertThat(Anonymizers.anonymize("bad jwt " + jwt + " here")).isEqualTo("bad jwt [token hidden] here");
        assertThat(sent("bad jwt " + jwt + " here")).isEqualTo("bad jwt [token hidden] here");
    }

    @Test
    void aTokenPrefixWithoutATokenIsLeftAsWritten() {
        assertThat(apply(TOKEN, "key ghp_short and sk_live_ only, eyJabc.def and task_live_abcdefgh"))
            .isEqualTo("key ghp_short and sk_live_ only, eyJabc.def and task_live_abcdefgh");
        assertThat(apply(TOKEN, "Task sk_live_updates1 failed and field rk_live_ranking1 is missing"))
            .isEqualTo("Task sk_live_updates1 failed and field rk_live_ranking1 is missing");
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
        "a+", "/a/a a/", "/a/a.b a/", "\\\\a\\", "Users\\a ", "tokens:\n- ", "password: a ", "\npassword: ", "pass=",
        "ghp_", "xoxb-a", "eyJaaaaa.", "Authorization: Basic ", "http://discord.com/api/webhooks/1/", "database '",
        "select a from the ", "delete from the ", "from the join ", "from the a set ", "tokens:\n- a\n\n",
        "tokens:\n- a\n#\n", "\"pass\": ", "pass=>", "pass=1", "Authorization=[Basic ", "Authorization: Basic a ",
        "/a/a to ", "\\\\a.b$\\",
    })
    void aLongAdversarialTextIsAnonymizedQuickly(String unit) {
        String text = unit.repeat(100_000 / unit.length() + 1);

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
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
