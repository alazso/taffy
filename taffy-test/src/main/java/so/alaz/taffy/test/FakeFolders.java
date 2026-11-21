package so.alaz.taffy.test;

import org.jetbrains.annotations.Nullable;
import org.mockito.MockMakers;
import org.mockito.MockSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.spi.FileSystemProvider;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** Folder stand-ins for failures a real folder cannot be made to show on every operating system. */
public final class FakeFolders {

    private FakeFolders() {
    }

    /**
     * A folder that gives {@code stream} when it is listed. When {@code stream} is {@code null}, it fails
     * instead with an {@link IOException} whose message is {@code access denied}.
     *
     * <p>The folder is a Mockito mock of {@link Path} on a mocked file system, so nothing is read from disk:
     * <ul>
     *   <li>{@code Files.isDirectory} is {@code true} and its attributes say it is a directory, while
     *       {@code Files.exists} and {@code Files.notExists} are both {@code false}.</li>
     *   <li>{@code resolve} with a {@code String} gives the folder itself, so a subfolder of it is the same
     *       folder.</li>
     *   <li>{@code Files.newDirectoryStream}, with or without a filter, gives {@code stream} as it is. No filter
     *       is applied to it.</li>
     *   <li>{@code getFileName}, {@code toAbsolutePath}, {@code normalize} and {@code resolve} with a
     *       {@code Path} return {@code null}. Every other method of the path and of its file system gives
     *       Mockito's default answer.</li>
     * </ul>
     *
     * <p>The stubbings are lenient: a test under Mockito's strict stubs, such as one run with
     * {@code MockitoExtension}, does not fail for the stubbed methods it leaves uncalled.
     */
    public static Path unlistable(@Nullable DirectoryStream<Path> stream) {
        BasicFileAttributes directory = mock(BasicFileAttributes.class, subclass());
        when(directory.isDirectory()).thenReturn(true);
        Answer<Object> answer = call -> switch (call.getMethod().getName()) {
            // readAttributes also has an overload that takes attribute names and returns a Map.
            case "readAttributes", "readAttributesIfExists" ->
                call.getMethod().getReturnType().isInstance(directory) ? directory : RETURNS_DEFAULTS.answer(call);
            case "newDirectoryStream" -> {
                if (stream == null) {
                    throw new IOException("access denied");
                }
                yield stream;
            }
            default -> RETURNS_DEFAULTS.answer(call);
        };
        FileSystemProvider provider = mock(FileSystemProvider.class, subclass().defaultAnswer(answer));
        FileSystem fileSystem = mock(FileSystem.class, subclass());
        when(fileSystem.provider()).thenReturn(provider);
        Path path = mock(Path.class, subclass());
        when(path.getFileSystem()).thenReturn(fileSystem);
        when(path.resolve(anyString())).thenReturn(path);
        return path;
    }

    /** Lenient mocks made by subclassing, so the JDK classes they stand in for are not retransformed. */
    private static MockSettings subclass() {
        return withSettings().mockMaker(MockMakers.SUBCLASS).strictness(Strictness.LENIENT);
    }
}
