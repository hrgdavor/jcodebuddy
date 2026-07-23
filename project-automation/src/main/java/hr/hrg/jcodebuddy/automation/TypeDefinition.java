package hr.hrg.jcodebuddy.automation;

import java.util.List;
import java.util.Map;

public record TypeDefinition(
        String qualifiedName,
        String simpleName,
        List<String> fields,
        Map<String, String> fieldTypes
) {
}
