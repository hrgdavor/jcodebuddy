package hr.hrg.jcodebuddy.engine.meta;

import java.util.Optional;

public interface SourceMetadata {
    String findType(String qualifiedName);
    Optional<SourceMetadata> findByPath(String path);
}
