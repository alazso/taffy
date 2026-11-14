package so.alaz.taffy.platform;

import java.util.UUID;

/** The thread a task handed to a {@link Dispatcher} runs on. */
public sealed interface ThreadTag {

    /** The global region thread. */
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
