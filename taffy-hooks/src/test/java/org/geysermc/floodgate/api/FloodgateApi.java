package org.geysermc.floodgate.api;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stands in for the interface of that name in Floodgate's API, with the two methods the Floodgate adapter reads.
 * {@link #INSTANCE} is empty until a test sets it, as Floodgate's instance is until Floodgate has enabled.
 */
public interface FloodgateApi {

    /** What {@link #getInstance} returns. */
    AtomicReference<FloodgateApi> INSTANCE = new AtomicReference<>();

    static FloodgateApi getInstance() {
        return INSTANCE.get();
    }

    boolean isFloodgatePlayer(UUID uuid);
}
