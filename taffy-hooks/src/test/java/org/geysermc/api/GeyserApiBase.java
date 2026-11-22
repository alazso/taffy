package org.geysermc.api;

import java.util.UUID;

/** Stands in for the interface of that name in Geyser's base API, with the method the Geyser adapter reads. */
public interface GeyserApiBase {

    boolean isBedrockPlayer(UUID uuid);
}
