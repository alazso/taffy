package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class DetectedTest {

    private static HookStatus status(HookState state) {
        return new HookStatus("Vault", state, "1.7", null);
    }

    @Test
    void anOnStateCarriesItsProvider() {
        assertThat(new Detected<>(status(HookState.ON), "provider").provider()).isEqualTo("provider");
        assertThat(new Detected<>(status(HookState.LOADING), "provider").provider()).isEqualTo("provider");
    }

    @Test
    void everyOtherStateCarriesNoProvider() {
        assertThat(EnumSet.range(HookState.SWITCHED_OFF, HookState.FAILED)).contains(HookState.NOT_REGISTERED);
        for (HookState state : EnumSet.range(HookState.SWITCHED_OFF, HookState.FAILED)) {
            assertThat(new Detected<String>(status(state), null).provider()).as(state.name()).isNull();
            assertThatThrownBy(() -> new Detected<>(status(state), "provider"))
                .as(state.name())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("state " + state + " takes no provider");
        }
    }

    @Test
    void anOnStateWithoutAProviderIsRefused() {
        assertThatThrownBy(() -> new Detected<String>(status(HookState.ON), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("state ON needs a provider");
        assertThatThrownBy(() -> new Detected<String>(status(HookState.LOADING), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("state LOADING needs a provider");
    }

    @Test
    void theStatusIsRequired() {
        assertThatThrownBy(() -> new Detected<String>(null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("status");
        assertThatThrownBy(() -> new Detected<String>(null, null, mock(Plugin.class)))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("status");
    }

    @Test
    void aBoundStatusReadsNotEnabledWhileItsPluginIsDisabledAndKeepsTheProvider() {
        Plugin vault = mock(Plugin.class);
        when(vault.isEnabled()).thenReturn(true);
        HookStatus found = status(HookState.ON);
        Detected<String> detected = new Detected<>(found, "provider", vault);

        assertThat(detected.status()).isSameAs(found);
        assertThat(detected.bound()).isSameAs(vault);

        when(vault.isEnabled()).thenReturn(false);
        assertThat(detected.status()).isEqualTo(new HookStatus("Vault", HookState.NOT_ENABLED, null, null));
        assertThat(detected.status().describe()).isEqualTo("Vault installed but not enabled");
        assertThat(detected.provider()).isEqualTo("provider");

        when(vault.isEnabled()).thenReturn(true);
        assertThat(detected.status()).isSameAs(found);
    }

    @Test
    void aStatusGivenToThePublicConstructorIsFixed() {
        HookStatus found = status(HookState.ON);
        Detected<String> detected = new Detected<>(found, "provider");

        assertThat(detected.bound()).isNull();
        assertThat(detected.status()).isSameAs(found);
    }

    @Test
    void aStatusThatStoppedDetectionIsFixedWhateverItsPlugin() {
        Plugin vault = mock(Plugin.class);
        HookStatus stopped = status(HookState.API_MISSING);
        Detected<String> detected = new Detected<>(stopped, null, null);

        assertThat(detected.status()).isSameAs(stopped);
        assertThatThrownBy(() -> new Detected<String>(stopped, null, vault))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a bound plugin needs a provider");
        verifyNoInteractions(vault);
    }
}
