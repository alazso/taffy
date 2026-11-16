package so.alaz.taffy.test;

import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.hidden.NamesAnItemStack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedClasspathTest {

    private static final String MARKER = "simulated.library.Marker";
    private static final String OTHER = "simulated.library.Other";
    private static final String OWN = "so.alaz.taffy.test.hidden.";
    private static final String ITEMS = "org.bukkit.inventory.";

    @Test
    void aNamedClassLoadsAndHasNoMembers() throws Exception {
        ClassLoader loader = SimulatedClasspath.with(MARKER);

        Class<?> marker = Class.forName(MARKER, false, loader);

        assertThat(marker.getName()).isEqualTo(MARKER);
        assertThat(marker.getClassLoader()).isSameAs(loader);
        assertThat(marker.getSuperclass()).isEqualTo(Object.class);
        assertThat(marker.getDeclaredFields()).isEmpty();
        assertThat(marker.getDeclaredMethods()).isEmpty();
        assertThat(marker.getDeclaredConstructors()).isEmpty();
    }

    @Test
    void everyNamedClassLoadsAndTheSameClassIsGivenEachTime() throws Exception {
        ClassLoader loader = SimulatedClasspath.with(MARKER, OTHER, MARKER);

        Class<?> marker = Class.forName(MARKER, false, loader);

        assertThat(Class.forName(MARKER, false, loader)).isSameAs(marker);
        assertThat(Class.forName(OTHER, false, loader).getName()).isEqualTo(OTHER);
    }

    @Test
    void aClassThatWasNotNamedIsNotFound() {
        ClassLoader loader = SimulatedClasspath.with(MARKER);

        assertThatThrownBy(() -> Class.forName(OTHER, false, loader))
            .isInstanceOf(ClassNotFoundException.class)
            .hasMessage(OTHER);
        assertThatThrownBy(() -> Class.forName(MARKER, false, SimulatedClasspath.with()))
            .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void aNamedClassIsAbsentFromTheParentAndFromAnotherSimulatedLoader() throws Exception {
        Class.forName(MARKER, false, SimulatedClasspath.with(MARKER));

        assertThatThrownBy(() -> Class.forName(MARKER, false, SimulatedClasspath.class.getClassLoader()))
            .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName(MARKER, false, SimulatedClasspath.with(OTHER)))
            .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void classesOfTheParentStillLoad() throws Exception {
        ClassLoader loader = SimulatedClasspath.with(MARKER);

        assertThat(loader.getParent()).isSameAs(SimulatedClasspath.class.getClassLoader());
        assertThat(Class.forName(CapturedLog.class.getName(), false, loader)).isSameAs(CapturedLog.class);
        assertThat(Class.forName("java.util.List", false, loader)).isSameAs(java.util.List.class);
    }

    @Test
    void aNamedClassTheParentKnowsLoadsTheRealClass() throws Exception {
        ClassLoader loader = SimulatedClasspath.with(CapturedLog.class.getName());

        assertThat(Class.forName(CapturedLog.class.getName(), false, loader)).isSameAs(CapturedLog.class);
    }

    @Test
    void aClassBelowTheOwnPrefixIsDefinedByTheHidingLoader() throws Exception {
        ClassLoader loader = SimulatedClasspath.hiding(OWN);

        Class<?> copy = Class.forName(NamesAnItemStack.class.getName(), false, loader);

        assertThat(copy.getName()).isEqualTo(NamesAnItemStack.class.getName());
        assertThat(copy.getClassLoader()).isSameAs(loader);
        assertThat(copy).isNotSameAs(NamesAnItemStack.class);
        assertThat(Class.forName(NamesAnItemStack.class.getName(), false, loader)).isSameAs(copy);
        assertThat(copy.getDeclaredMethod("item").getReturnType()).isSameAs(ItemStack.class);
        assertThatThrownBy(() -> Class.forName(OWN + "Missing", false, loader))
            .isInstanceOf(ClassNotFoundException.class)
            .hasMessage(OWN + "Missing");
    }

    @Test
    void aCopyHasTheCodeSourceOfTheOriginalClass() throws Exception {
        ClassLoader loader = SimulatedClasspath.hiding("so.alaz.taffy.test.");
        ClassLoader jarLoader = SimulatedClasspath.hiding(ITEMS + "ItemFlag");

        Class<?> copy = Class.forName(NamesAnItemStack.class.getName(), false, loader);
        Class<?> sameDirectory = Class.forName(SimulatedClasspathTest.class.getName(), false, loader);
        Class<?> fromJar = Class.forName(ITEMS + "ItemFlag", false, jarLoader);

        assertThat(copy.getProtectionDomain().getCodeSource().getLocation().toURI())
            .isEqualTo(NamesAnItemStack.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertThat(copy.getProtectionDomain().getClassLoader()).isSameAs(loader);
        assertThat(sameDirectory.getProtectionDomain()).isSameAs(copy.getProtectionDomain());
        assertThat(fromJar.getClassLoader()).isSameAs(jarLoader);
        assertThat(fromJar.getProtectionDomain().getCodeSource().getLocation().toURI())
            .isEqualTo(ItemFlag.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    @Test
    void aNameBelowAHiddenPrefixIsNotFound() {
        ClassLoader loader = SimulatedClasspath.hiding(OWN, ITEMS, OWN + "NamesAn");

        assertThatThrownBy(() -> Class.forName(ITEMS + "ItemStack", false, loader))
            .isInstanceOf(ClassNotFoundException.class)
            .hasMessage(ITEMS + "ItemStack");
        assertThatThrownBy(() -> Class.forName(NamesAnItemStack.class.getName(), false, loader))
            .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void aCopyThatNamesAHiddenClassFailsWhenItsMembersAreRead() throws Exception {
        Class<?> copy = Class.forName(NamesAnItemStack.class.getName(), true, SimulatedClasspath.hiding(OWN, ITEMS));

        assertThatThrownBy(copy::getDeclaredMethods)
            .isInstanceOf(NoClassDefFoundError.class)
            .hasMessageContaining("org/bukkit/inventory/ItemStack");
    }

    @Test
    void aNameOutsideBothPrefixesIsTheParentsClass() throws Exception {
        ClassLoader loader = SimulatedClasspath.hiding(OWN, ITEMS);

        assertThat(loader.getParent()).isSameAs(SimulatedClasspath.class.getClassLoader());
        assertThat(Class.forName(CapturedLog.class.getName(), false, loader)).isSameAs(CapturedLog.class);
        assertThat(Class.forName("org.bukkit.Material", false, loader)).isSameAs(Material.class);
        assertThat(Class.forName("java.util.List", false, loader)).isSameAs(java.util.List.class);
    }

    @Test
    void aBlankOwnPrefixIsRefused() {
        assertThatThrownBy(() -> SimulatedClasspath.hiding(" ", ITEMS))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("own prefix is blank");
        assertThatThrownBy(() -> SimulatedClasspath.hiding(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankHiddenPrefixIsRefused() {
        assertThatThrownBy(() -> SimulatedClasspath.hiding(OWN, ITEMS, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hidden prefix is blank");
        assertThatThrownBy(() -> SimulatedClasspath.hiding(OWN, " "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNullPrefixIsRefused() {
        assertThatThrownBy(() -> SimulatedClasspath.hiding(null, ITEMS))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SimulatedClasspath.hiding(OWN, (String[]) null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SimulatedClasspath.hiding(OWN, ITEMS, null))
            .isInstanceOf(NullPointerException.class);
    }
}
