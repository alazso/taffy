package so.alaz.taffy.hooks;

import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;
import java.util.function.Predicate;

// NexoItems.idFromItem reads the id from the item's own PDC, so it answers before Nexo has loaded its items.
final class NexoProvider implements CustomItemProvider {

    private final Predicate<String> exists;
    private final Function<String, @Nullable ItemStack> build;
    private final Function<ItemStack, @Nullable String> idOf;

    NexoProvider(Predicate<String> exists, Function<String, @Nullable ItemStack> build,
                 Function<ItemStack, @Nullable String> idOf) {
        this.exists = exists;
        this.build = build;
        this.idOf = idOf;
    }

    static CustomItemProvider create() {
        ApiLink.staticMethod(NexoItems.class, "idFromItem", String.class, ItemStack.class);
        ApiLink.staticMethod(NexoItems.class, "exists", boolean.class, String.class);
        ApiLink.staticMethod(NexoItems.class, "itemFromId", ItemBuilder.class, String.class);
        ApiLink.instanceMethod(ItemBuilder.class, "build", ItemStack.class);
        return new NexoProvider(
            id -> NexoItems.exists(id),
            id -> {
                ItemBuilder builder = NexoItems.itemFromId(id);
                return builder == null ? null : builder.build();
            },
            item -> NexoItems.idFromItem(item));
    }

    @Override
    public @Nullable ItemStack create(String id) {
        return exists.test(id) ? build.apply(id) : null;
    }

    @Override
    public @Nullable String id(ItemStack item) {
        return idOf.apply(item);
    }
}
