package so.alaz.taffy.hooks;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.test.FakeItems;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A provider that knows the native ids set per {@link FakeItems} type, with a ready flag, a reads-ids flag, a
 * throwable to throw (checked ones included) and a count of {@code create} and {@code id} calls. {@code create}
 * builds a new {@link FakeItems} item of the type set for the id, amount 1.
 */
final class FakeProvider implements CustomItemProvider {

    final AtomicInteger calls = new AtomicInteger();
    volatile boolean ready = true;
    volatile boolean readsIds = true;
    volatile @Nullable Throwable failure;
    private final Map<String, String> idsByType = new ConcurrentHashMap<>();

    FakeProvider id(String type, String id) {
        idsByType.put(type, id);
        return this;
    }

    @Override
    public boolean ready() {
        return ready;
    }

    @Override
    public boolean readsIds() {
        return readsIds;
    }

    @Override
    public @Nullable ItemStack create(String id) {
        call();
        for (Map.Entry<String, String> entry : idsByType.entrySet()) {
            if (entry.getValue().equals(id)) {
                return FakeItems.item(entry.getKey(), 1);
            }
        }
        return null;
    }

    @Override
    public @Nullable String id(ItemStack item) {
        call();
        String type = FakeItems.type(item);
        return type == null ? null : idsByType.get(type);
    }

    private void call() {
        calls.incrementAndGet();
        Throwable thrown = failure;
        if (thrown != null) {
            throw FakeProvider.<RuntimeException>sneaky(thrown);
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> T sneaky(Throwable failure) throws T {
        throw (T) failure;
    }
}
