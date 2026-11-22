package org.geysermc.geyser.api;

import org.geysermc.api.GeyserApiBase;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Stands in for the interface of that name in Geyser's API, with the accessor the Geyser adapter reads.
 * {@link #REGISTERED} is empty until a test sets it, and {@link #api} then throws as Geyser's does before Geyser
 * has registered its API.
 */
public interface GeyserApi extends GeyserApiBase {

    /** What {@link #api} returns. */
    AtomicReference<GeyserApi> REGISTERED = new AtomicReference<>();

    static GeyserApi api() {
        GeyserApi registered = REGISTERED.get();
        if (registered == null) {
            throw new RuntimeException("Api has not been registered yet!");
        }
        return registered;
    }
}
