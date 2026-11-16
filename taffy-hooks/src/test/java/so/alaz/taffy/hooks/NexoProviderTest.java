package so.alaz.taffy.hooks;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

final class NexoProviderTest {

    private final ItemStack ruby = mock(ItemStack.class);
    private final ItemStack dirt = mock(ItemStack.class);
    private final List<String> built = new ArrayList<>();

    private NexoProvider provider(Map<String, ItemStack> known, boolean builds) {
        return new NexoProvider(
            known::containsKey,
            id -> {
                built.add(id);
                return builds ? known.get(id) : null;
            },
            item -> item == ruby ? "ruby" : null);
    }

    @Test
    void aNexoItemGetsItsBareIdAndAnyOtherItemNone() {
        NexoProvider provider = provider(Map.of(), true);

        assertThat(provider.id(ruby)).isEqualTo("ruby");
        assertThat(provider.id(dirt)).isNull();
    }

    @Test
    void aKnownIdGivesTheBuiltItem() {
        NexoProvider provider = provider(Map.of("ruby", ruby), true);

        assertThat(provider.create("ruby")).isSameAs(ruby);
        assertThat(built).containsExactly("ruby");
    }

    @Test
    void anUnknownIdGivesNoItemWithoutBuilding() {
        NexoProvider provider = provider(Map.of("ruby", ruby), true);

        assertThat(provider.create("sapphire")).isNull();
        assertThat(built).isEmpty();
    }

    @Test
    void aKnownIdWithoutABuilderGivesNoItem() {
        NexoProvider provider = provider(Map.of("ruby", ruby), false);

        assertThat(provider.create("ruby")).isNull();
        assertThat(built).containsExactly("ruby");
    }

    @Test
    void theProviderIsReadyBeforeNexoLoadsItsItems() {
        assertThat(provider(Map.of(), true).ready()).isTrue();
    }

    @Test
    void theFactoryLinksTheApi() {
        CustomItemProvider provider = NexoProvider.create();

        assertThat(provider).isInstanceOf(NexoProvider.class);
        assertThat(provider.ready()).isTrue();
    }
}
