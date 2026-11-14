package so.alaz.taffy.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Reads YAML and never replaces a document it cannot use with an empty configuration. A file that cannot be
 * read, a missing one included, bytes that are not UTF-8, text the YAML parser rejects, a top level that is not
 * a mapping and a second document in the same text each give a {@link ConfigLoadException}.
 *
 * <p>An anchor used inside the node it names, and aliases that together repeat more than 100000 nodes, also
 * give a {@link ConfigLoadException}: building either one would exhaust the stack or the memory.
 *
 * <p>A key written twice in one mapping is not an error: the last one is kept. Where it holds a section, the
 * keys of the earlier section are dropped. A key that holds a dot is read as a path of nested keys, so
 * {@code "a.b": 1} sets {@code b} inside a section {@code a}: a section {@code a} written after it in the same
 * mapping replaces it, and one written before it gains the key. YAML reads an unquoted key {@code yes},
 * {@code no}, {@code on}, {@code off}, {@code true} or {@code false} as a boolean, so it becomes the key {@code true} or {@code false}, and an unquoted number key
 * becomes the text of that number.
 *
 * <p>The returned {@link YamlConfiguration} keeps the comments of the document, so saving it writes them back.
 * It is a new object on every call and is not thread-safe.
 */
public final class StrictYaml {

    /** The most nodes that aliases may add to a document beyond those its text holds. */
    static final int MAX_REPEATED_NODES = 100_000;

    private StrictYaml() {
    }

    /**
     * Parses {@code text} as a YAML document with its comments kept. Empty text is an empty configuration.
     *
     * @param source the name of the document in the failure message, such as {@code config.yml}
     * @throws ConfigLoadException when the parser rejects the text, when the top level is not a mapping, when
     *     the text holds more than one document, when an anchor is used inside the node it names or when
     *     aliases repeat too many nodes. The message is {@code source}, then {@code " is not valid YAML: "}, then
     *     the reason. The reason is the simple class name of the failure when the parser gave no message.
     */
    public static YamlConfiguration parse(String text, String source) throws ConfigLoadException {
        checkAliases(text, source);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        // Paper's loadFromString builds sections outside its own catch, so a bad tag or key escapes as a
        // RuntimeException.
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException | RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            throw new ConfigLoadException(source + " is not valid YAML: " + reason, failure);
        }
        return yaml;
    }

    /**
     * Reads {@code file} as UTF-8 and parses it as {@link #parse(String, String)} does. The read blocks the
     * calling thread.
     *
     * @param source the name of the file in the failure message, such as {@code config.yml}
     * @throws ConfigLoadException when the file holds a byte sequence that is not UTF-8 (the message is
     *     {@code source}, then {@code " is not saved as UTF-8"}), when it cannot be read for any other reason,
     *     a missing file included (the message is {@code "cannot read "}, the path of the file, {@code ": "},
     *     then the simple class name and the message of the I/O failure), or when its text does not parse
     */
    public static YamlConfiguration read(Path file, String source) throws ConfigLoadException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException notUtf8) {
            throw new ConfigLoadException(source + " is not saved as UTF-8", notUtf8);
        } catch (IOException failure) {
            throw new ConfigLoadException("cannot read " + file + ": " + describe(failure), failure);
        }
        return parse(text, source);
    }

    /**
     * Walks the node graph of {@code text} with every alias followed. A text the composer rejects is left to
     * {@link YamlConfiguration#loadFromString}, which reports it.
     */
    private static void checkAliases(String text, String source) throws ConfigLoadException {
        // The limits match those YamlConfiguration composes with, so any text it accepts is checked here.
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(Integer.MAX_VALUE);
        options.setCodePointLimit(Integer.MAX_VALUE);
        options.setNestingDepthLimit(100);
        Node root;
        try {
            root = new Yaml(options).compose(new StringReader(text));
        } catch (RuntimeException rejected) {
            return;
        }
        if (root == null) {
            return;
        }
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Node> onPath = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Iterator<Node>> path = new ArrayDeque<>();
        Deque<Node> nodes = new ArrayDeque<>();
        long repeated = 0;
        seen.add(root);
        onPath.add(root);
        nodes.push(root);
        path.push(children(root).iterator());
        while (!path.isEmpty()) {
            if (!path.peek().hasNext()) {
                path.pop();
                onPath.remove(nodes.pop());
                continue;
            }
            Node child = path.peek().next();
            if (!onPath.add(child)) {
                throw new ConfigLoadException(source + " is not valid YAML: an anchor is used inside the value it"
                    + " names");
            }
            if (!seen.add(child) && ++repeated > MAX_REPEATED_NODES) {
                throw new ConfigLoadException(source + " is not valid YAML: its aliases repeat more than "
                    + MAX_REPEATED_NODES + " values");
            }
            nodes.push(child);
            path.push(children(child).iterator());
        }
    }

    private static List<Node> children(Node node) {
        List<Node> children = new ArrayList<>();
        if (node instanceof MappingNode mapping) {
            for (NodeTuple entry : mapping.getValue()) {
                children.add(entry.getKeyNode());
                children.add(entry.getValueNode());
            }
        } else if (node instanceof SequenceNode sequence) {
            children.addAll(sequence.getValue());
        }
        return children;
    }

    private static String describe(IOException failure) {
        return failure.getClass().getSimpleName() + " " + failure.getMessage();
    }
}
