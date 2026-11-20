package so.alaz.taffy.hooks;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.FakeItems;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class CustomItemProviderTest {

    @Test
    void aProviderIsReadyUnlessItSaysOtherwise() {
        CustomItemProvider provider = new CustomItemProvider() {
            @Override
            public @Nullable ItemStack create(String id) {
                return null;
            }

            @Override
            public @Nullable String id(ItemStack item) {
                return null;
            }
        };

        assertThat(provider.ready()).isTrue();
        assertThat(provider.idsReady()).isTrue();
        assertThat(provider.readsIds()).isTrue();
    }

    @Test
    void aProviderReadsIdsOnceItIsReadyUnlessItSaysOtherwise() {
        CustomItemProvider provider = new CustomItemProvider() {
            @Override
            public boolean ready() {
                return false;
            }

            @Override
            public @Nullable ItemStack create(String id) {
                return null;
            }

            @Override
            public @Nullable String id(ItemStack item) {
                return null;
            }
        };

        assertThat(provider.idsReady()).isFalse();
    }

    @Test
    void theFakeReadsAndBuildsTheIdsSetForItsTypes() {
        FakeProvider provider = new FakeProvider().id("ruby", "gems:ruby");

        ItemStack created = provider.create("gems:ruby");

        assertThat(FakeItems.type(created)).isEqualTo("ruby");
        assertThat(created.getAmount()).isEqualTo(1);
        assertThat(provider.id(FakeItems.item("ruby", 5))).isEqualTo("gems:ruby");
        assertThat(provider.id(FakeItems.item("stone", 1))).isNull();
        assertThat(provider.create("gems:opal")).isNull();
        assertThat(provider.calls).hasValue(4);
    }

    @Test
    void theFakeThrowsWhatItIsGivenAndCountsTheCall() {
        FakeProvider provider = new FakeProvider().id("ruby", "gems:ruby");
        provider.failure = new IOException("broken");

        assertThatThrownBy(() -> provider.id(FakeItems.item("ruby", 1))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> provider.create("gems:ruby")).isInstanceOf(IOException.class);
        assertThat(provider.calls).hasValue(2);
    }

    @Test
    void theFakeReportsItsReadyFlag() {
        FakeProvider provider = new FakeProvider();
        provider.ready = false;

        assertThat(provider.ready()).isFalse();
        assertThat(provider.idsReady()).isFalse();
        provider.idsWhileLoading = true;
        assertThat(provider.idsReady()).isTrue();
    }
}
