package hr.hrg.jcodebuddy.automation;

import java.util.List;
import java.util.Map;

public interface RuntimeTypeView {
    String qualifiedName();
    String simpleName();
    List<String> fields();
    Map<String, String> fieldTypes();
    List<String> methods();
    List<String> modifiers();
}
