package hr.hrg.jcodebuddy.automation;

import hr.hrg.hipster.entity.tooling.meta.SourceMetadata;
import hr.hrg.jcodebuddy.codegen.TypeResolver;

/**
 * A {@link TypeResolver} that builds its knowledge from metadata passes.
 *
 * <p>The resolver itself is part of the shared generator API, because a generator is given one and does
 * not care which project supplied it. What stays here is the side this project owns: the ability to
 * <b>index</b> a metadata pass into the resolver. That is the split the rule asks for — the reusable
 * seam is promoted, and the project keeps the part that is its own.
 */
public interface MetadataTypeResolver extends TypeResolver {

    /** Add what a metadata pass knows, so later lookups can resolve against it. */
    void index(SourceMetadata metadata);
}
