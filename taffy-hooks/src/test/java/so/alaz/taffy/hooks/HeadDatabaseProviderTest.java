package so.alaz.taffy.hooks;

import me.arcaniax.hdb.api.HeadDatabaseAPI;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class HeadDatabaseProviderTest {

    private final ItemStack head = mock(ItemStack.class);

    @Test
    void aKnownIdGivesItsHead() {
        try (MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class, (api, context) -> {
            when(api.isHead("1234")).thenReturn(true);
            when(api.getItemHead("1234")).thenReturn(head);
        })) {
            CustomItemProvider provider = HeadDatabaseProvider.create();

            assertThat(provider.create("1234")).isSameAs(head);
            assertThat(apis.constructed()).hasSize(1);
        }
    }

    @Test
    void anUnknownIdGivesNoHeadWithoutAskingForOne() {
        try (MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class, (api, context) -> {
            when(api.isHead("9999")).thenReturn(false);
            when(api.getItemHead("9999")).thenReturn(head);
        })) {
            CustomItemProvider provider = HeadDatabaseProvider.create();

            assertThat(provider.create("9999")).isNull();
            HeadDatabaseAPI api = apis.constructed().getFirst();
            verify(api).isHead("9999");
            verify(api, never()).getItemHead("9999");
        }
    }

    @Test
    void theIdIsPassedAsGiven() {
        try (MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class, (api, context) -> {
            when(api.isHead("hdb:1234")).thenReturn(false);
        })) {
            CustomItemProvider provider = HeadDatabaseProvider.create();

            assertThat(provider.create("hdb:1234")).isNull();
            verify(apis.constructed().getFirst()).isHead("hdb:1234");
        }
    }

    @Test
    void noItemIsReadBackAsAHead() {
        try (MockedConstruction<HeadDatabaseAPI> apis = mockConstruction(HeadDatabaseAPI.class)) {
            CustomItemProvider provider = HeadDatabaseProvider.create();

            assertThat(provider.readsIds()).isFalse();
            assertThat(provider.id(head)).isNull();
            assertThat(apis.constructed()).isEmpty();
        }
    }

    @Test
    void theFactoryLinksTheApi() {
        CustomItemProvider provider = HeadDatabaseProvider.create();

        assertThat(provider).isInstanceOf(HeadDatabaseProvider.class);
        assertThat(provider.ready()).isTrue();
    }
}
