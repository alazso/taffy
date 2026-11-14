package so.alaz.taffy.test;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static so.alaz.taffy.test.FakeItems.item;

class FakeItemsTest {

    @Test
    void theAmountIsReadAndSetLikeARealStack() {
        ItemStack stack = item("gem", 5);

        assertThat(stack.getAmount()).isEqualTo(5);

        stack.setAmount(12);

        assertThat(stack.getAmount()).isEqualTo(12);
    }

    @Test
    void anItemIsEmptyOnceItsAmountIsZeroOrLess() {
        ItemStack stack = item("gem", 1);

        assertThat(stack.isEmpty()).isFalse();

        stack.setAmount(0);

        assertThat(stack.isEmpty()).isTrue();

        stack.setAmount(-3);

        assertThat(stack.isEmpty()).isTrue();
        assertThat(item("gem", 0).isEmpty()).isTrue();
    }

    @Test
    void theMaximumStackSizeIsSixtyFourUnlessGiven() {
        assertThat(item("gem", 1).getMaxStackSize()).isEqualTo(64);
        assertThat(item("pearl", 1, 16).getMaxStackSize()).isEqualTo(16);
        assertThat(item(Material.DIAMOND, 1).getMaxStackSize()).isEqualTo(64);
        assertThat(item(Material.ENDER_PEARL, 1, 16).getMaxStackSize()).isEqualTo(16);
    }

    @Test
    void itemsAreSimilarWhenTheirTypesMatchWhateverTheirAmounts() {
        ItemStack gem = item("gem", 5);

        assertThat(gem.isSimilar(item("gem", 40))).isTrue();
        assertThat(gem.isSimilar(gem)).isTrue();
        assertThat(gem.isSimilar(item("pearl", 5))).isFalse();
    }

    @Test
    void anItemIsNotSimilarToNullOrToAnItemMadeElsewhere() {
        ItemStack gem = item("gem", 5);

        assertThat(gem.isSimilar(null)).isFalse();
        assertThat(gem.isSimilar(mock(ItemStack.class))).isFalse();
    }

    @Test
    void anItemIsEqualOnlyToItself() {
        ItemStack gem = item("gem", 5);

        assertThat(gem).isEqualTo(gem);
        assertThat(gem).isNotEqualTo(item("gem", 5));
    }

    @Test
    void asQuantityGivesANewItemOfTheSameTypeWithThatAmount() {
        ItemStack pearl = item("pearl", 3, 16);

        ItemStack copy = pearl.asQuantity(9);

        assertThat(copy).isNotSameAs(pearl);
        assertThat(copy.getAmount()).isEqualTo(9);
        assertThat(copy.getMaxStackSize()).isEqualTo(16);
        assertThat(copy.isSimilar(pearl)).isTrue();
        assertThat(FakeItems.type(copy)).isEqualTo("pearl");
        assertThat(pearl.getAmount()).isEqualTo(3);
    }

    @Test
    void asOneGivesANewItemOfTheSameTypeWithAmountOne() {
        ItemStack pearl = item("pearl", 3, 16);

        ItemStack one = pearl.asOne();

        assertThat(one).isNotSameAs(pearl);
        assertThat(one.getAmount()).isEqualTo(1);
        assertThat(one.getMaxStackSize()).isEqualTo(16);
        assertThat(one.isSimilar(pearl)).isTrue();
        assertThat(pearl.getAmount()).isEqualTo(3);
    }

    @Test
    void cloneGivesANewItemWithTheAmountOfThatMomentAndItsOwnCount() {
        ItemStack pearl = item("pearl", 3, 16);
        pearl.setAmount(7);

        ItemStack copy = pearl.clone();
        copy.setAmount(2);

        assertThat(copy).isNotSameAs(pearl);
        assertThat(copy.getAmount()).isEqualTo(2);
        assertThat(copy.getMaxStackSize()).isEqualTo(16);
        assertThat(copy.isSimilar(pearl)).isTrue();
        assertThat(pearl.getAmount()).isEqualTo(7);
    }

    @Test
    void anItemMadeFromATypeNameHasNoMaterial() {
        ItemStack gem = item("gem", 5);

        assertThat(gem.getType()).isNull();
        assertThat(gem.clone().getType()).isNull();
    }

    @Test
    void aMaterialItemKeepsItsMaterialOnEveryCopy() {
        ItemStack diamond = item(Material.DIAMOND, 5);

        assertThat(diamond.getType()).isEqualTo(Material.DIAMOND);
        assertThat(diamond.asQuantity(2).getType()).isEqualTo(Material.DIAMOND);
        assertThat(diamond.asOne().getType()).isEqualTo(Material.DIAMOND);
        assertThat(diamond.clone().getType()).isEqualTo(Material.DIAMOND);
        assertThat(diamond.getAmount()).isEqualTo(5);
    }

    @Test
    void aMaterialItemHasTheMaterialNameInLowercaseAsItsType() {
        ItemStack diamond = item(Material.DIAMOND, 5);

        assertThat(FakeItems.type(diamond)).isEqualTo("diamond");
        assertThat(diamond.isSimilar(item("diamond", 1))).isTrue();
        assertThat(diamond.isSimilar(item(Material.EMERALD, 5))).isFalse();
    }

    @Test
    void theTypeOfAMaterialItemIsTheSameUnderATurkishDefaultLocale() throws Exception {
        JvmDefaults.withLocale(JvmDefaults.TURKISH, () -> {
            assertThat(FakeItems.type(item(Material.IRON_INGOT, 1))).isEqualTo("iron_ingot");
            assertThat(FakeItems.type(item(Material.IRON_INGOT, 1, 16))).isEqualTo("iron_ingot");
        });
    }

    @Test
    void typeIsTheNameTheItemWasMadeWithAndNullForAnItemMadeElsewhere() {
        assertThat(FakeItems.type(item("gem", 5))).isEqualTo("gem");
        assertThat(FakeItems.type(item("pearl", 5, 16))).isEqualTo("pearl");
        assertThat(FakeItems.type(mock(ItemStack.class))).isNull();
    }

    @Test
    void toStringShowsTheTypeAndTheCurrentAmount() {
        ItemStack gem = item("gem", 5);

        assertThat(gem.toString()).isEqualTo("gem x 5");

        gem.setAmount(2);

        assertThat(gem).hasToString("gem x 2");
        assertThat(item(Material.DIAMOND, 3)).hasToString("diamond x 3");
    }
}
