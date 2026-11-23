package so.alaz.taffy.hooks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class HookStatusTest {

    @Test
    void eachStateDescribesItselfWithThePluginName() {
        assertThat(new HookStatus("Nexo", HookState.SWITCHED_OFF, null, null).describe())
            .isEqualTo("Nexo switched off");
        assertThat(new HookStatus("Nexo", HookState.NOT_INSTALLED, null, null).describe())
            .isEqualTo("Nexo not installed");
        assertThat(new HookStatus("Nexo", HookState.NOT_ENABLED, null, null).describe())
            .isEqualTo("Nexo installed but not enabled");
        assertThat(new HookStatus("Nexo", HookState.NOT_VISIBLE, "2.0", null).describe())
            .isEqualTo("Nexo 2.0 not visible to this plugin");
        assertThat(new HookStatus("Nexo", HookState.API_MISSING, "2.0", null).describe())
            .isEqualTo("Nexo 2.0 lacks the API that is read");
        assertThat(new HookStatus("Vault", HookState.NOT_REGISTERED, "1.7.3", null).describe())
            .isEqualTo("Vault 1.7.3 has nothing registered with it");
        assertThat(new HookStatus("Nexo", HookState.FAILED, "2.0", null).describe())
            .isEqualTo("Nexo 2.0 could not be hooked");
        assertThat(new HookStatus("Nexo", HookState.ON, "2.0", null).describe()).isEqualTo("Nexo on");
        assertThat(new HookStatus("ItemsAdder", HookState.LOADING, "4.0", null).describe())
            .isEqualTo("ItemsAdder on, waiting for its items to load");
    }

    @Test
    void aVersionThatWasNotReadShowsAsUnknown() {
        assertThat(new HookStatus("Vault", HookState.API_MISSING, null, null).describe())
            .isEqualTo("Vault unknown lacks the API that is read");
        assertThat(new HookStatus("Vault", HookState.FAILED, null, null).describe())
            .isEqualTo("Vault unknown could not be hooked");
        assertThat(new HookStatus("Vault", HookState.NOT_VISIBLE, null, null).describe())
            .isEqualTo("Vault unknown not visible to this plugin");
        assertThat(new HookStatus("Vault", HookState.NOT_REGISTERED, null, null).describe())
            .isEqualTo("Vault unknown has nothing registered with it");
    }

    @Test
    void theVersionIsLeftOutOfTheStatesThatDoNotShowIt() {
        assertThat(new HookStatus("Vault", HookState.NOT_ENABLED, "1.7", null).describe())
            .isEqualTo("Vault installed but not enabled");
        assertThat(new HookStatus("Vault", HookState.ON, "1.7", null).describe()).isEqualTo("Vault on");
    }

    @Test
    void theReasonIsKeptAndNeverDescribed() {
        String reason = "Vault is installed, but no economy plugin has registered with it. Install one, such as"
            + " EssentialsX.";
        HookStatus status = new HookStatus("Vault", HookState.NOT_REGISTERED, "1.7.3", reason);

        assertThat(status.reason()).isEqualTo(reason);
        assertThat(status.describe()).isEqualTo("Vault 1.7.3 has nothing registered with it");
        for (HookState state : HookState.values()) {
            assertThat(new HookStatus("Vault", state, "1.7.3", reason).describe()).as(state.name())
                .isEqualTo(new HookStatus("Vault", state, "1.7.3", null).describe());
        }
    }

    @Test
    void aMissingReasonIsAcceptedAndABlankOneIsRefused() {
        assertThat(new HookStatus("Vault", HookState.ON, "1.7", null).reason()).isNull();
        for (String blank : new String[] {"", " ", "\t\n"}) {
            assertThatThrownBy(() -> new HookStatus("Vault", HookState.FAILED, "1.7", blank))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("reason is blank");
        }
    }

    @Test
    void thePluginAndTheStateAreRequired() {
        assertThatThrownBy(() -> new HookStatus(null, HookState.ON, "1.0", null))
            .isInstanceOf(NullPointerException.class).hasMessage("plugin");
        assertThatThrownBy(() -> new HookStatus("Vault", null, "1.0", null))
            .isInstanceOf(NullPointerException.class).hasMessage("state");
    }

    @Test
    void theLineIsTheDescriptionAloneWithoutAReason() {
        for (HookState state : HookState.values()) {
            HookStatus status = new HookStatus("Vault", state, "1.7.3", null);

            assertThat(status.line()).as(state.name()).isEqualTo(status.describe());
        }
    }

    @Test
    void theLineJoinsTheDescriptionAndTheReasonWithAFullStopAndASpace() {
        String reason = "Vault is installed, but no economy plugin has registered with it. Install one, such as"
            + " EssentialsX.";

        assertThat(new HookStatus("Vault", HookState.NOT_REGISTERED, "1.7.3", reason).line())
            .isEqualTo("Vault 1.7.3 has nothing registered with it. Vault is installed, but no economy plugin has"
                + " registered with it. Install one, such as EssentialsX.");
        for (HookState state : HookState.values()) {
            HookStatus status = new HookStatus("Vault", state, null, reason);

            assertThat(status.line()).as(state.name()).isEqualTo(status.describe() + ". " + reason);
        }
    }
}
