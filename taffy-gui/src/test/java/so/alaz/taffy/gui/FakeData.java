package so.alaz.taffy.gui;

import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// An item's PDC as Paper reads it: has(key, type) is true only for a value of that type, and get with another type
// throws, as a real container does.
final class FakeData implements PersistentDataContainerView {

    private final Map<NamespacedKey, Object> values = new LinkedHashMap<>();

    FakeData put(NamespacedKey key, Object value) {
        values.put(key, value);
        return this;
    }

    @Override
    public <P, C> boolean has(NamespacedKey key, PersistentDataType<P, C> type) {
        return type.getComplexType().isInstance(values.get(key));
    }

    @Override
    public boolean has(NamespacedKey key) {
        return values.containsKey(key);
    }

    @Override
    public <P, C> @Nullable C get(NamespacedKey key, PersistentDataType<P, C> type) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (!type.getComplexType().isInstance(value)) {
            throw new IllegalArgumentException("The found tag instance does not match the type of " + key);
        }
        return type.getComplexType().cast(value);
    }

    @Override
    public <P, C> C getOrDefault(NamespacedKey key, PersistentDataType<P, C> type, C defaultValue) {
        C value = get(key, type);
        return value == null ? defaultValue : value;
    }

    @Override
    public Set<NamespacedKey> getKeys() {
        return Set.copyOf(values.keySet());
    }

    @Override
    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public void copyTo(PersistentDataContainer other, boolean replace) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PersistentDataAdapterContext getAdapterContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] serializeToBytes() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSize() {
        return values.size();
    }
}
