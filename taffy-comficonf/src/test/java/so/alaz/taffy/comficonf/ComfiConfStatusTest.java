package so.alaz.taffy.comficonf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ComfiConfStatusTest {

    @Test
    void eachStateDescribesItselfWithTheVersion() {
        assertThat(new ComfiConfStatus(ComfiConfState.SWITCHED_OFF, null, null).describe())
            .isEqualTo("ComfiConf switched off");
        assertThat(new ComfiConfStatus(ComfiConfState.NOT_INSTALLED, null, null).describe())
            .isEqualTo("ComfiConf not installed");
        assertThat(new ComfiConfStatus(ComfiConfState.NOT_ENABLED, null, null).describe())
            .isEqualTo("ComfiConf installed but not enabled");
        assertThat(new ComfiConfStatus(ComfiConfState.NOT_VISIBLE, "1.2.0", null).describe())
            .isEqualTo("ComfiConf 1.2.0 not visible to this plugin");
        assertThat(new ComfiConfStatus(ComfiConfState.API_MISSING, "1.2.0", null).describe())
            .isEqualTo("ComfiConf 1.2.0 lacks the API this plugin needs");
        assertThat(new ComfiConfStatus(ComfiConfState.NOT_REGISTERED, "1.2.0", null).describe())
            .isEqualTo("ComfiConf 1.2.0 installed, not registered");
        assertThat(new ComfiConfStatus(ComfiConfState.FAILED, "1.2.0", null).describe())
            .isEqualTo("ComfiConf 1.2.0 could not register this plugin's settings");
        assertThat(new ComfiConfStatus(ComfiConfState.ON, "1.2.0", null).describe()).isEqualTo("ComfiConf on");
    }

    @Test
    void aVersionThatWasNotReadShowsAsUnknown() {
        assertThat(new ComfiConfStatus(ComfiConfState.NOT_VISIBLE, null, null).describe())
            .isEqualTo("ComfiConf unknown not visible to this plugin");
        assertThat(new ComfiConfStatus(ComfiConfState.API_MISSING, null, null).describe())
            .isEqualTo("ComfiConf unknown lacks the API this plugin needs");
        assertThat(new ComfiConfStatus(ComfiConfState.NOT_REGISTERED, null, null).describe())
            .isEqualTo("ComfiConf unknown installed, not registered");
        assertThat(new ComfiConfStatus(ComfiConfState.FAILED, null, null).describe())
            .isEqualTo("ComfiConf unknown could not register this plugin's settings");
    }

    @Test
    void theVersionIsLeftOutOfTheStatesThatDoNotShowIt() {
        assertThat(new ComfiConfStatus(ComfiConfState.SWITCHED_OFF, "1.2.0", null).describe())
            .isEqualTo("ComfiConf switched off");
        assertThat(new ComfiConfStatus(ComfiConfState.NOT_INSTALLED, "1.2.0", null).describe())
            .isEqualTo("ComfiConf not installed");
        assertThat(new ComfiConfStatus(ComfiConfState.NOT_ENABLED, "1.2.0", null).describe())
            .isEqualTo("ComfiConf installed but not enabled");
        assertThat(new ComfiConfStatus(ComfiConfState.ON, null, null).describe()).isEqualTo("ComfiConf on");
    }

    @Test
    void theReasonIsKeptAndNeverDescribed() {
        String reason = "Registering with ComfiConf failed, so these settings are edited in their files only. The"
            + " server log holds the error.";
        ComfiConfStatus status = new ComfiConfStatus(ComfiConfState.FAILED, "1.2.0", reason);

        assertThat(status.reason()).isEqualTo(reason);
        assertThat(status.describe()).isEqualTo("ComfiConf 1.2.0 could not register this plugin's settings");
        for (ComfiConfState state : ComfiConfState.values()) {
            assertThat(new ComfiConfStatus(state, "1.2.0", reason).describe()).as(state.name())
                .isEqualTo(new ComfiConfStatus(state, "1.2.0", null).describe())
                .doesNotContain(reason);
        }
    }

    @Test
    void aMissingReasonIsAcceptedAndABlankOneIsRefused() {
        assertThat(new ComfiConfStatus(ComfiConfState.ON, "1.2.0", null).reason()).isNull();
        for (String blank : new String[] {"", " ", "\t\n"}) {
            assertThatThrownBy(() -> new ComfiConfStatus(ComfiConfState.FAILED, "1.2.0", blank))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("reason is blank");
        }
    }

    @Test
    void theStateIsRequired() {
        assertThatThrownBy(() -> new ComfiConfStatus(null, "1.2.0", null))
            .isInstanceOf(NullPointerException.class).hasMessage("state");
    }
}
