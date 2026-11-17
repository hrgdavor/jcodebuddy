package hr.hrg.jcodebuddy.engine.index;

import java.util.List;

/**
 * One generated artifact and the source rows it was generated from.
 *
 * <p>The class index only says a file is generated. This record carries the graph an incremental pass and a
 * UI need: which type the artifact is, which generator and options produced it, and which input types it
 * depends on. It is extended metadata and never a file-local row.</p>
 *
 * @param type        the generated artifact's FQN
 * @param path        the generated file's module-relative path
 * @param generator   the generator id
 * @param version     the generator version/revision
 * @param inputs      input type FQNs
 * @param options     option names whose values affect the output
 * @param optionsHash a hash/fingerprint of the option values
 */
public record ArtifactRecord(String type, String path, String generator, String version,
                             List<String> inputs, List<String> options, String optionsHash) {

    public ArtifactRecord {
        type = type == null ? "" : type;
        path = path == null ? "" : path;
        generator = generator == null ? "" : generator;
        version = version == null ? "" : version;
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        options = options == null ? List.of() : List.copyOf(options);
        optionsHash = optionsHash == null ? "" : optionsHash;
    }
}
