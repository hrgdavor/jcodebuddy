package hr.hrg.hipster.entity.tooling.meta;

import java.util.Optional;

public interface SourceMetadata {
    String findType(String qualifiedName);
    Optional<SourceMetadata> findByPath(String path);
}
