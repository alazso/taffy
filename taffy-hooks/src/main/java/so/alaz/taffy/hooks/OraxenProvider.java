package so.alaz.taffy.hooks;

import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.items.ItemBuilder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;
import java.util.function.Predicate;

// OraxenItems.getIdByItem reads the id from the item's own PDC. OraxenItems has a static initializer that needs the
// Oraxen plugin, so create only links its methods and the class initializes on the first call through the functions.
final class OraxenProvider implements CustomItemProvider {

    private final Predicate<String> exists;
    private final Function<String, @Nullable ItemStack> build;
    private final Function<ItemStack, @Nullable String> idOf;

    OraxenProvider(Predicate<String> exists, Function<String, @Nullable ItemStack> build,
                   Function<ItemStack, @Nullable String> idOf) {
        this.exists = exists;
        this.build = build;
        this.idOf = idOf;
    }

    static CustomItemProvider create() {
        ApiLink.staticMethod(OraxenItems.class, "getIdByItem", String.class, ItemStack.class);
        ApiLink.staticMethod(OraxenItems.class, "exists", boolean.class, String.class);
        ApiLink.staticMethod(OraxenItems.class, "getItemById", ItemBuilder.class, String.class);
        ApiLink.instanceMethod(ItemBuilder.class, "build", ItemStack.class);
        return new OraxenProvider(
            id -> OraxenItems.exists(id),
            id -> {
                ItemBuilder builder = OraxenItems.getItemById(id);
                return builder == null ? null : builder.build();
            },
            item -> OraxenItems.getIdByItem(item));
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
