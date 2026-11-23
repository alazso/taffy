package so.alaz.taffy.hooks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

// Resolves the exact methods and fields a hook reads, so a removed method or field, or a changed parameter, return or
// field type, throws a NoSuchMethodError or a NoSuchFieldError when the hook starts instead of on every call. A lookup
// does not initialize the owner class.
final class ApiLink {

    private ApiLink() {
    }

    // The class of that name on the given loader, not initialized. A class the loader does not find throws a
    // NoClassDefFoundError.
    static Class<?> type(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException missing) {
            NoClassDefFoundError error = new NoClassDefFoundError(name);
            error.initCause(missing);
            throw error;
        }
    }

    static MethodHandle staticMethod(Class<?> owner, String name, Class<?> returns, Class<?>... parameters) {
        return link(owner, name, MethodType.methodType(returns, parameters), true);
    }

    // A method the owner inherits from a superclass or a superinterface is found too.
    static MethodHandle instanceMethod(Class<?> owner, String name, Class<?> returns, Class<?>... parameters) {
        return link(owner, name, MethodType.methodType(returns, parameters), false);
    }

    static void staticField(Class<?> owner, String name, Class<?> type) {
        try {
            MethodHandles.publicLookup().findStaticGetter(owner, name, type);
        } catch (NoSuchFieldException | IllegalAccessException missing) {
            NoSuchFieldError error = new NoSuchFieldError(owner.getName() + "." + name + ":" + type.getSimpleName());
            error.initCause(missing);
            throw error;
        }
    }

    static void constructor(Class<?> owner, Class<?>... parameters) {
        MethodType type = MethodType.methodType(void.class, parameters);
        try {
            MethodHandles.publicLookup().findConstructor(owner, type);
        } catch (NoSuchMethodException | IllegalAccessException missing) {
            NoSuchMethodError error = new NoSuchMethodError(owner.getName() + ".<init>" + type);
            error.initCause(missing);
            throw error;
        }
    }

    private static MethodHandle link(Class<?> owner, String name, MethodType type, boolean isStatic) {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            return isStatic ? lookup.findStatic(owner, name, type) : lookup.findVirtual(owner, name, type);
        } catch (NoSuchMethodException | IllegalAccessException missing) {
            NoSuchMethodError error = new NoSuchMethodError(owner.getName() + "." + name + type);
            error.initCause(missing);
            throw error;
        }
    }
}
