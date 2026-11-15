package so.alaz.taffy.storage;

/** The server images the container suites run against. */
final class DatabaseImages {

    static final String MYSQL = "mysql:8.4";
    static final String MARIADB = "mariadb:11.4";
    static final String POSTGRES = "postgres:17-alpine";

    private DatabaseImages() {
    }
}
