package so.alaz.taffy.hooks;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

// Resolves the exact methods a hook calls, so a removed method or a changed parameter or return type throws a
// NoSuchMethodError when the hook starts instead of on every call. A lookup does not initialize the owner class.
final class ApiLink {

    private ApiLink() {
    }

    static void staticMethod(Class<?> owner, String name, Class<?> returns, Class<?>... parameters) {
        link(owner, name, MethodType.methodType(returns, parameters), true);
    }

    static void instanceMethod(Class<?> owner, String name, Class<?> returns, Class<?>... parameters) {
        link(owner, name, MethodType.methodType(returns, parameters), false);
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

    private static void link(Class<?> owner, String name, MethodType type, boolean isStatic) {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            if (isStatic) {
                lookup.findStatic(owner, name, type);
            } else {
                lookup.findVirtual(owner, name, type);
            }
        } catch (NoSuchMethodException | IllegalAccessException missing) {
            NoSuchMethodError error = new NoSuchMethodError(owner.getName() + "." + name + type);
            error.initCause(missing);
            throw error;
        }
    }
}
