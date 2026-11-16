package so.alaz.taffy.metrics;

import dev.faststats.ErrorTracker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnonymizersTest {

    @Test
    void thereAreEightRulesOfAPatternAndAReplacement() {
        assertThat(Anonymizers.RULES).hasDimensions(8, 2);
    }

    @Test
    void anEmailAddressIsHidden() {
        assertThat(apply(0, "mail admin@example.org failed")).isEqualTo("mail [email hidden] failed");
    }

    @Test
    void aBearerTokenIsHidden() {
        assertThat(apply(1, "Authorization: Bearer abc.DEF-123_~+/=")).isEqualTo("Authorization: Bearer [token hidden]");
    }

    @Test
    void anAwsAccessKeyIdIsHidden() {
        assertThat(apply(2, "key AKIAABCDEFGHIJKLMNOP used")).isEqualTo("key [aws-key hidden] used");
    }

    @Test
    void aUuidIsHidden() {
        assertThat(apply(3, "player 123e4567-e89b-42d3-a456-426614174000 left"))
            .isEqualTo("player [uuid hidden] left");
    }

    @Test
    void aSecretQueryValueIsRedactedAndTheKeyKept() {
        assertThat(apply(4, "GET /hook?api_key=s3cr3t&page=2")).isEqualTo("GET /hook?api_key=[redacted]&page=2");
        assertThat(apply(4, "GET /hook?x=1&token=abc")).isEqualTo("GET /hook?x=1&token=[redacted]");
        assertThat(apply(4, "GET /hook?apikey=abc&secret=def")).isEqualTo("GET /hook?apikey=[redacted]&secret=[redacted]");
        assertThat(apply(4, "GET /hook?page=2")).isEqualTo("GET /hook?page=2");
    }

    @Test
    void aJdbcUrlIsRedacted() {
        assertThat(apply(5, "cannot open jdbc:mysql://db.local:3306/game?user=root failed"))
            .isEqualTo("cannot open jdbc:[redacted] failed");
    }

    @Test
    void anSqlStatementIsHiddenToTheEndOfTheLine() {
        assertThat(apply(6, "failed: select * from players where name = 'x'\nnext"))
            .isEqualTo("failed: [sql hidden]\nnext");
        assertThat(apply(6, "the selection is empty")).isEqualTo("the selection is empty");
    }

    @Test
    void aWindowsPathIsHidden() {
        assertThat(apply(7, "cannot read C:\\Server\\plugins\\data.db now")).isEqualTo("cannot read [path hidden] now");
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
