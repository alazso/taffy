package so.alaz.taffy.platform;

import java.util.UUID;

/** The thread a task handed to a {@link Dispatcher} runs on. */
public sealed interface ThreadTag {

    /**
     * The global region thread. On Paper it is the main thread, which owns every chunk and entity. On Folia it
     * owns no chunk and no entity, so world and entity work in a task dispatched here must hop to the region
     * thread of its location or to the {@link #entity} thread of its player.
     */
    ThreadTag GLOBAL = new Global();

    /** An async thread. */
    ThreadTag ASYNC = new Async();

    /** The region thread owning the online player with this id. */
    static ThreadTag entity(UUID id) {
        return new Entity(id);
    }

    /** The tag of {@link #GLOBAL}. */
    record Global() implements ThreadTag {
        @Override
        public String toString() {
            return "GLOBAL";
        }
    }

    /** The tag of {@link #ASYNC}. */
    record Async() implements ThreadTag {
        @Override
        public String toString() {
            return "ASYNC";
        }
    }

    /** The tag {@link #entity} returns. Two are equal when their ids are. */
    record Entity(UUID id) implements ThreadTag {
        @Override
        public String toString() {
            return "ENTITY(" + id + ")";
        }
    }
}
