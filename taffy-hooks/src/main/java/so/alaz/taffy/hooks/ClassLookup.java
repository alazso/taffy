package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

// The outcome of looking up a class by name without initializing it. A lookup that threw is neither present nor
// absent: the server searches the class loaders of other plugins, so a plugin whose jar was closed after it failed to
// enable makes the lookup throw, and failure holds what it threw.
record ClassLookup(boolean present, @Nullable Throwable failure) {

    static final ClassLookup PRESENT = new ClassLookup(true, null);
    static final ClassLookup ABSENT = new ClassLookup(false, null);

    ClassLookup {
        if (present && failure != null) {
            throw new IllegalArgumentException("a present class has no failure");
        }
    }

    static ClassLookup failed(Throwable failure) {
        return new ClassLookup(false, Objects.requireNonNull(failure, "failure"));
    }

    // A null loader is the bootstrap class loader.
    static ClassLookup of(String name, @Nullable ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            return PRESENT;
        } catch (ClassNotFoundException absent) {
            return ABSENT;
        } catch (LinkageError | RuntimeException failure) {
            return failed(failure);
        }
    }
}
