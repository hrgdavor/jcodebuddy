package hr.hrg.jcodebuddy.automation;

import java.util.List;
import java.util.Map;

public interface TypeResolver {

    TypeDefinition resolve(String qualifiedName);

    List<String> knownPackages();

    static TypeResolver empty() {
        return new EmptyTypeResolver();
    }

    final class EmptyTypeResolver implements TypeResolver {
        @Override
        public TypeDefinition resolve(String qualifiedName) {
            return null;
        }

        @Override
        public List<String> knownPackages() {
            return List.of();
        }
    }
}
