package hr.hrg.jcodebuddy.engine.index;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The extended metadata layer written beside {@code classes.json}.
 *
 * <p>These tables depend on more than one file's bytes — resolution, the classpath, the generator graph — so
 * they are kept separate from the file-local class index. A tool that only needs the base facts can ignore
 * this layer; a UI, a generator and an incremental pass read it together.</p>
 */
public record ExtendedMetadataIndex(String module, ArtifactsIndex artifacts,
                                    TypeReferencesIndex typeReferences, PackagesIndex packages,
                                    ModulesIndex modules, TypeUsesIndex typeUses) {

    public ExtendedMetadataIndex {
        module = module == null ? "" : module;
        artifacts = artifacts == null ? ArtifactsIndex.empty(module) : artifacts;
        typeReferences = typeReferences == null ? new TypeReferencesIndex(module, java.util.List.of())
                : typeReferences;
        packages = packages == null ? new PackagesIndex(module, java.util.List.of()) : packages;
        modules = modules == null ? new ModulesIndex(module, java.util.List.of()) : modules;
        typeUses = typeUses == null ? new TypeUsesIndex(module, java.util.List.of()) : typeUses;
    }

    public static ExtendedMetadataIndex empty(String module) {
        return new ExtendedMetadataIndex(module, null, null, null, null, null);
    }

    public void write(Path indexDir) throws IOException {
        artifacts.write(indexDir);
        typeReferences.write(indexDir);
        packages.write(indexDir);
        modules.write(indexDir);
        typeUses.write(indexDir);
    }

    public static ExtendedMetadataIndex read(Path indexDir) throws IOException {
        return new ExtendedMetadataIndex(
                "",
                ArtifactsIndex.read(indexDir),
                TypeReferencesIndex.read(indexDir),
                PackagesIndex.read(indexDir),
                ModulesIndex.read(indexDir),
                TypeUsesIndex.read(indexDir));
    }
}
