package so.alaz.taffy.config;

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compares the values a parsed YAML document holds. */
final class Values {

    private Values() {
    }

    /**
     * Whether {@code first} and {@code second} hold the same content. Arrays, such as the {@code byte[]} YAML builds
     * for a {@code !!binary} value, compare by their elements, lists entry by entry in order and maps key by key.
     * Two {@link ConfigurationSerializable} objects of one class that are not equal compare by what they serialize
     * to. Anything else compares with {@link Object#equals}, so a whole number and a decimal of the same amount
     * differ.
     */
    static boolean same(@Nullable Object first, @Nullable Object second) {
        if (Objects.deepEquals(first, second)) {
            return true;
        }
        if (first instanceof List<?> one && second instanceof List<?> other) {
            if (one.size() != other.size()) {
                return false;
            }
            Iterator<?> others = other.iterator();
            for (Object entry : one) {
                if (!same(entry, others.next())) {
                    return false;
                }
            }
            return true;
        }
        if (first instanceof Map<?, ?> one && second instanceof Map<?, ?> other) {
            if (!one.keySet().equals(other.keySet())) {
                return false;
            }
            for (Map.Entry<?, ?> entry : one.entrySet()) {
                if (!same(entry.getValue(), other.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (first instanceof ConfigurationSerializable one && second instanceof ConfigurationSerializable other) {
            return one.getClass() == other.getClass() && same(one.serialize(), other.serialize());
        }
        return false;
    }
}
