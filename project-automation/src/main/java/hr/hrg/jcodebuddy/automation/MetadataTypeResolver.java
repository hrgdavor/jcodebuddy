package hr.hrg.jcodebuddy.automation;

import hr.hrg.hipster.entity.tooling.meta.SourceMetadata;

public interface MetadataTypeResolver extends TypeResolver {
    void index(SourceMetadata metadata);
}
