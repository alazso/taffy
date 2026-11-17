package so.alaz.taffy.comficonf;

import org.bukkit.configuration.file.YamlConfiguration;
import so.alaz.taffy.config.ConfigLoadException;
import so.alaz.taffy.config.StrictYaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class YamlBytes {

    /** The largest file {@link #read(Path, String)} accepts, in bytes. */
    static final int MAX_FILE_BYTES = 16 * 1024 * 1024;

    private static final String BYTE_ORDER_MARK = "﻿";

    private YamlBytes() {
    }

    /**
     * Reads the bytes of {@code file}, at most {@link #MAX_FILE_BYTES} of them. The read blocks the calling thread.
     *
     * @param source the name of the file in the failure message, such as {@code config.yml}
     * @throws IOException when the file cannot be read, a missing file included
     * @throws ConfigLoadException when the file is larger than 16 MiB (the message is {@code source}, then
     *     {@code " is larger than 16 MiB"})
     */
    static byte[] read(Path file, String source) throws IOException, ConfigLoadException {
        byte[] content;
        try (InputStream in = Files.newInputStream(file)) {
            content = in.readNBytes(MAX_FILE_BYTES + 1);
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new ConfigLoadException(source + " is larger than 16 MiB");
        }
        return content;
    }

    /**
     * Parses YAML bytes with comments kept, so the document can be saved again without losing them. A
     * {@link YamlConfiguration} is not thread-safe: every callback parses its own.
     *
     * <p>Decoding is strict: bytes that are not UTF-8, an unpaired surrogate included, fail with
     * {@link ConfigLoadException} and are never replaced.
     *
     * @param source the name of the document in the failure message, such as {@code config.yml}
     * @throws ConfigLoadException when the bytes are not UTF-8 (the message is {@code source}, then
     *     {@code " is not saved as UTF-8"}) or when {@link StrictYaml#parse(String, String)} rejects the text
     */
    static YamlConfiguration parse(byte[] content, String source) throws ConfigLoadException {
        return StrictYaml.parse(decode(content, source), source);
    }

    private static String decode(byte[] content, String source) throws ConfigLoadException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
        } catch (CharacterCodingException e) {
            throw new ConfigLoadException(source + " is not saved as UTF-8", e);
        }
    }

    /**
     * Encodes an emitted document with the line endings and the leading byte order mark of the file it replaces.
     * YAML emission always writes LF and no byte order mark, which would otherwise rewrite every line of a file saved
     * with CRLF and drop the mark of a file that starts with one.
     */
    static byte[] encodeLike(String yaml, byte[] original) {
        String text = usesCarriageReturns(original) ? yaml.replace("\r\n", "\n").replace("\n", "\r\n") : yaml;
        if (startsWithByteOrderMark(original) && !text.startsWith(BYTE_ORDER_MARK)) {
            text = BYTE_ORDER_MARK + text;
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean startsWithByteOrderMark(byte[] content) {
        return content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
            && (content[2] & 0xFF) == 0xBF;
    }

    /** Whether most of the line breaks in {@code content} are CRLF. */
    static boolean usesCarriageReturns(byte[] content) {
        int breaks = 0;
        int carriageReturns = 0;
        for (int i = 0; i < content.length; i++) {
            if (content[i] == '\n') {
                breaks++;
                if (i > 0 && content[i - 1] == '\r') {
                    carriageReturns++;
                }
            }
        }
        return carriageReturns * 2 > breaks;
    }
}
