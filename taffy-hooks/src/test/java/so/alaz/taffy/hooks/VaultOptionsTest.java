package so.alaz.taffy.hooks;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.ErrorReports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class VaultOptionsTest {

    private final Plugin owner = mock(Plugin.class);
    private final ErrorReports errors = new ErrorReports();

    @Test
    void theFactoryGivesALookupThatIsSwitchedOnWithTheEconomysScaleOnTheGlobalThread() {
        VaultOptions options = VaultOptions.of(owner, errors, true);

        assertThat(options.owner()).isSameAs(owner);
        assertThat(options.errors()).isSameAs(errors);
        assertThat(options.folia()).isTrue();
        assertThat(options.switchedOn()).isTrue();
        assertThat(options.scale()).isNull();
        assertThat(options.threadRule()).isEqualTo(VaultOptions.ThreadRule.GLOBAL);
        assertThat(options.fundsCheck()).isEqualTo(VaultOptions.FundsCheck.EMULATED);
        assertThat(VaultOptions.of(owner, errors, false).folia()).isFalse();
    }

    @Test
    void eachMethodThatTakesAValueGivesACopyAndLeavesTheOptionsUnchanged() {
        VaultOptions options = VaultOptions.of(owner, errors, false);

        VaultOptions changed = options.switchedOn(false).scale(3).threadRule(VaultOptions.ThreadRule.ENTITY)
            .fundsCheck(VaultOptions.FundsCheck.NATIVE);

        assertThat(changed.switchedOn()).isFalse();
        assertThat(changed.scale()).isEqualTo(3);
        assertThat(changed.threadRule()).isEqualTo(VaultOptions.ThreadRule.ENTITY);
        assertThat(changed.fundsCheck()).isEqualTo(VaultOptions.FundsCheck.NATIVE);
        assertThat(changed.scale(1).switchedOn(true).threadRule(VaultOptions.ThreadRule.GLOBAL).fundsCheck())
            .isEqualTo(VaultOptions.FundsCheck.NATIVE);
        assertThat(changed.owner()).isSameAs(owner);
        assertThat(changed.errors()).isSameAs(errors);
        assertThat(changed.folia()).isFalse();
        assertThat(changed.scale(null).scale()).isNull();
        assertThat(options.switchedOn()).isTrue();
        assertThat(options.scale()).isNull();
        assertThat(options.threadRule()).isEqualTo(VaultOptions.ThreadRule.GLOBAL);
        assertThat(options.fundsCheck()).isEqualTo(VaultOptions.FundsCheck.EMULATED);
    }

    @Test
    void aMissingValueOrAScaleOutsideZeroToFourIsRefused() {
        VaultOptions options = VaultOptions.of(owner, errors, false);

        assertThatThrownBy(() -> VaultOptions.of(null, errors, false))
            .isInstanceOf(NullPointerException.class).hasMessage("owner");
        assertThatThrownBy(() -> VaultOptions.of(owner, null, false))
            .isInstanceOf(NullPointerException.class).hasMessage("errors");
        assertThatThrownBy(() -> options.threadRule(null))
            .isInstanceOf(NullPointerException.class).hasMessage("threadRule");
        assertThatThrownBy(() -> options.fundsCheck(null))
            .isInstanceOf(NullPointerException.class).hasMessage("fundsCheck");
        assertThatThrownBy(() -> options.scale(-1))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("a scale runs from 0 to 4, not -1");
        assertThatThrownBy(() -> options.switchedOn(false).scale(5))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("a scale runs from 0 to 4, not 5");
        assertThat(options.scale(0).scale()).isZero();
        assertThat(options.scale(4).scale()).isEqualTo(4);
    }

    @Test
    void theTextNamesTheOwnerAndEachValue() {
        when(owner.getName()).thenReturn("Shop");

        assertThat(VaultOptions.of(owner, errors, true).scale(2)).hasToString("VaultOptions[owner=Shop, folia=true,"
            + " switchedOn=true, scale=2, threadRule=GLOBAL, fundsCheck=EMULATED]");
    }
}
