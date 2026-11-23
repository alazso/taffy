package so.alaz.taffy.platform;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class RedactionTest {

    @Test
    void thereAreNineteenRulesThatCompile() {
        assertThat(Redaction.rules()).hasSize(19).allSatisfy(rule ->
            assertThatCode(() -> Pattern.compile(rule.pattern())).doesNotThrowAnyException());
        assertThatThrownBy(() -> Redaction.rules().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRuleNeedsBothParts() {
        assertThatThrownBy(() -> new Redaction.Rule(null, "x")).isInstanceOf(NullPointerException.class)
            .hasMessage("pattern");
        assertThatThrownBy(() -> new Redaction.Rule("x", null)).isInstanceOf(NullPointerException.class)
            .hasMessage("replacement");
    }

    @Test
    void aPoolFailureLosesItsUrlUserAndHost() {
        assertThat(Redaction.hide("Failed to initialize pool: Access denied for user 'mc_prod'@'db.example.net'"
            + " (using password: YES)"))
            .isEqualTo("Failed to initialize pool: Access denied for user [user hidden]@[host hidden]"
                + " (using password: YES)");
        assertThat(Redaction.hide("cannot open jdbc:mysql://root:hunter2@db.example.net:3306/shop now"))
            .isEqualTo("cannot open jdbc:[redacted] now");
        assertThat(Redaction.hide("FATAL: password authentication failed for user \"mc_prod\""))
            .isEqualTo("FATAL: password authentication failed for user [user hidden]");
        assertThat(Redaction.hide("Connection to db.myserver.host:5432 refused"))
            .isEqualTo("Connection to [host hidden]:5432 refused");
    }

    @Test
    void secretsPathsAndIdsAreHidden() {
        assertThat(Redaction.hide("login failed with password=hunter2 for 123e4567-e89b-12d3-a456-426614174000"))
            .isEqualTo("login failed with password=[redacted] for [uuid hidden]");
        assertThat(Redaction.hide("Cannot read C:\\Users\\John Smith\\server\\plugins\\Shop\\data.db"))
            .isEqualTo("Cannot read [path hidden]");
        assertThat(Redaction.hide("Cannot read /home/john/server/plugins/Shop/data.db"))
            .isEqualTo("Cannot read [path hidden]");
    }

    @Test
    void addressesAreHiddenAfterTheRules() {
        assertThat(Redaction.hide("Connect to 10.0.0.5:3306 timed out"))
            .isEqualTo("Connect to [address hidden]:3306 timed out");
        assertThat(Redaction.hide("peer 2001:db8::8a2e:370:7334 closed the connection"))
            .isEqualTo("peer [address hidden] closed the connection");
        assertThat(Redaction.hide("lock held since 12:34:56 today"))
            .isEqualTo("lock held since [address hidden] today");
        assertThat(Redaction.ADDRESS).isEqualTo("[address hidden]");
    }

    @Test
    void anAddressBeforeAFullStopIsHidden() {
        assertThat(Redaction.hide("Could not connect to 10.0.0.5."))
            .isEqualTo("Could not connect to [address hidden].");
        assertThat(Redaction.hide("Host '10.0.0.5' is blocked. Peer 10.0.0.6. Retrying"))
            .isEqualTo("Host '[address hidden]' is blocked. Peer [address hidden]. Retrying");
        assertThat(Redaction.hide("built from 1.2.3.4.5 today")).isEqualTo("built from 1.2.3.4.5 today");
    }

    @Test
    void aDoubleColonWithoutAHexDigitIsNoAddress() {
        String line = "Schema version 7 is newer than 5 :: refusing";

        assertThat(Redaction.hide(line)).isEqualTo(line);
        assertThat(Redaction.hide("bound to ::1 only")).isEqualTo("bound to [address hidden] only");
        assertThat(Redaction.hide("failed for key a::b")).isEqualTo("failed for key [address hidden]");
    }

    @Test
    void theRestOfAUnixPathBehindACharacterTheRuleDoesNotReadIsHidden() {
        assertThat(Redaction.hide("path /home/j\u00F6hn/server/plugins/Shop/data.db is not writable"))
            .isEqualTo("path [path hidden] is not writable");
        assertThat(Redaction.hide("/home/john+smith/server/data.db")).isEqualTo("[path hidden]");
        assertThat(Redaction.hide("'/home/john@pc/server/data.db' (No such file)"))
            .isEqualTo("'[path hidden]' (No such file)");
        assertThat(Redaction.hide("Cannot read /home/john/server/data.db: denied, see /var/log/x.log."))
            .isEqualTo("Cannot read [path hidden]: denied, see [path hidden]");
    }

    @Test
    void ordinaryTextStaysAsWritten() {
        String line = "The table shop_orders was set up by a newer version of the plugin (schema 7, this is 5)";

        assertThat(Redaction.hide(line)).isEqualTo(line);
        assertThat(Redaction.hide("")).isEmpty();
        assertThat(Redaction.hide("Paper 1.21.4 on Java 25.0.1")).isEqualTo("Paper 1.21.4 on Java 25.0.1");
    }

    @Test
    void aNullTextIsRefused() {
        assertThatThrownBy(() -> Redaction.hide(null)).isInstanceOf(NullPointerException.class).hasMessage("text");
    }
}
