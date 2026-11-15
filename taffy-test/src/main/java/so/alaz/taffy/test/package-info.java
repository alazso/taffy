/**
 * Helpers for testing Paper plugin code without a server.
 *
 * <p>Mockito is the only dependency the module brings to a test classpath.
 * {@link so.alaz.taffy.test.ApiRules} and {@link so.alaz.taffy.test.FakeItems} name Paper types, so a test that
 * uses them needs paper-api on its own test classpath. No class here needs a test framework or an assertion
 * library.
 *
 * <p>{@link so.alaz.taffy.test.ApiRules} checks the design rules of Taffy modules and rejects an anchor from any
 * other package.
 *
 * <p>{@link so.alaz.taffy.test.FakeItems} and {@link so.alaz.taffy.test.FakeFolders} return Mockito mocks.
 * {@link so.alaz.taffy.test.JvmDefaults} changes JVM-wide state and is not safe under parallel test execution.
 *
 * <p>{@link so.alaz.taffy.test.FaultyDataSource} needs a JDBC driver on the test runtime classpath.
 */
package so.alaz.taffy.test;
