package so.alaz.taffy.hooks;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.CapturedLog;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ApiLinkTest {

    private final CapturedLog log = new CapturedLog();

    public static final class Api {

        public static Api instance;

        public static String idFromItem(ItemStack item) {
            return "id";
        }

        public static Optional<String> idFor(ItemStack item) {
            return Optional.empty();
        }

        public String namespacedId() {
            return "id";
        }

        public Api() {
        }

        public Api(String name) {
        }
    }

    private static final class Everywhere implements Presence {

        private final Plugin plugin = mock(Plugin.class);

        Everywhere() {
            when(plugin.isEnabled()).thenReturn(true);
        }

        @Override
        public boolean installed(String plugin) {
            return true;
        }

        @Override
        public boolean enabled(String plugin) {
            return true;
        }

        @Override
        public ClassLookup classLookup(String name) {
            return ClassLookup.PRESENT;
        }

        @Override
        public ClassLookup classLookupIn(Plugin plugin, String name) {
            return ClassLookup.PRESENT;
        }

        @Override
        public String version(String plugin) {
            return "2.0";
        }

        @Override
        public @Nullable Plugin plugin(String name) {
            return plugin;
        }
    }

    @Test
    void methodsWithTheExpectedSignatureLink() {
        assertThatCode(() -> {
            ApiLink.staticMethod(Api.class, "idFromItem", String.class, ItemStack.class);
            ApiLink.instanceMethod(Api.class, "namespacedId", String.class);
        }).doesNotThrowAnyException();
    }

    @Test
    void aMissingMethodOrAChangedSignatureFailsToLink() {
        assertThatThrownBy(() -> ApiLink.staticMethod(Api.class, "itemId", String.class, ItemStack.class))
            .isInstanceOf(NoSuchMethodError.class)
            .hasMessage(Api.class.getName() + ".itemId(ItemStack)String")
            .hasCauseInstanceOf(NoSuchMethodException.class);
        assertThatThrownBy(() -> ApiLink.staticMethod(Api.class, "idFor", String.class, ItemStack.class))
            .isInstanceOf(NoSuchMethodError.class);
        assertThatThrownBy(() -> ApiLink.staticMethod(Api.class, "idFromItem", String.class, Object.class))
            .isInstanceOf(NoSuchMethodError.class);
        assertThatThrownBy(() -> ApiLink.staticMethod(Api.class, "namespacedId", String.class))
            .isInstanceOf(NoSuchMethodError.class);
        assertThatThrownBy(() -> ApiLink.instanceMethod(Api.class, "idFromItem", String.class, ItemStack.class))
            .isInstanceOf(NoSuchMethodError.class);
    }

    @Test
    void aStaticFieldOfTheExpectedTypeLinks() {
        assertThatCode(() -> ApiLink.staticField(Api.class, "instance", Api.class)).doesNotThrowAnyException();
    }

    @Test
    void aMissingStaticFieldOrAChangedTypeFailsToLink() {
        assertThatThrownBy(() -> ApiLink.staticField(Api.class, "plugin", Api.class))
            .isInstanceOf(NoSuchFieldError.class)
            .hasMessage(Api.class.getName() + ".plugin:Api")
            .hasCauseInstanceOf(NoSuchFieldException.class);
        assertThatThrownBy(() -> ApiLink.staticField(Api.class, "instance", String.class))
            .isInstanceOf(NoSuchFieldError.class);
    }

    @Test
    void aConstructorWithTheExpectedParametersLinks() {
        assertThatCode(() -> {
            ApiLink.constructor(Api.class);
            ApiLink.constructor(Api.class, String.class);
        }).doesNotThrowAnyException();
    }

    @Test
    void aMissingConstructorFailsToLink() {
        assertThatThrownBy(() -> ApiLink.constructor(Api.class, int.class))
            .isInstanceOf(NoSuchMethodError.class)
            .hasMessage(Api.class.getName() + ".<init>(int)void")
            .hasCauseInstanceOf(NoSuchMethodException.class);
        assertThatThrownBy(() -> ApiLink.constructor(Everywhere.class))
            .isInstanceOf(NoSuchMethodError.class)
            .hasCauseInstanceOf(IllegalAccessException.class);
    }

    @Test
    void aPluginWhoseApiChangedStaysOffAndTheOthersStart() {
        CustomItemProvider plain = new FakeProvider();
        Map<CustomItemPlugin, Function<Plugin, CustomItemProvider>> factories = Map.of(
            CustomItemPlugin.ITEMSADDER, bound -> plain,
            CustomItemPlugin.NEXO, bound -> {
                ApiLink.staticMethod(Api.class, "idFor", String.class, ItemStack.class);
                return plain;
            },
            CustomItemPlugin.ORAXEN, bound -> plain,
            CustomItemPlugin.HEADDATABASE, bound -> plain,
            CustomItemPlugin.CRAFTENGINE, bound -> plain,
            CustomItemPlugin.MMOITEMS, bound -> plain);
        Plugin owner = mock(Plugin.class);
        when(owner.getLogger()).thenReturn(log.logger);

        CustomItems items = CustomItems.detect(owner, new ErrorReports(), EnumSet.allOf(CustomItemPlugin.class),
            new Everywhere(), factories);

        assertThat(items.active()).containsExactly(CustomItemPlugin.ITEMSADDER, CustomItemPlugin.ORAXEN,
            CustomItemPlugin.HEADDATABASE, CustomItemPlugin.CRAFTENGINE, CustomItemPlugin.MMOITEMS);
        assertThat(log.lines()).containsExactly(
            "WARNING Nexo 2.0 could not be hooked (java.lang.NoSuchMethodError: " + Api.class.getName()
                + ".idFor(ItemStack)String), so it stays off.",
            "INFO Custom item plugins: ItemsAdder on; Nexo 2.0 could not be hooked; Oraxen on; HeadDatabase on;"
                + " CraftEngine on; MMOItems on.");
    }
}
