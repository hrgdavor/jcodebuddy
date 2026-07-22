package hr.hrg.hipster.entity.tooling.meta;

import java.util.List;

public record ViewMeta(String name, List<String> extendsTypes, Boolean read, Boolean write, List<Property> properties, int lineNumber) {
    public String getName() { return name; }
    public List<String> getExtendsTypes() { return extendsTypes; }
    public Boolean getRead() { return read; }
    public Boolean getWrite() { return write; }
    public List<Property> getProperties() { return properties; }
    public int getLineNumber() { return lineNumber; }
}
