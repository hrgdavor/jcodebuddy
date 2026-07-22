package hr.hrg.hipster.entity.tooling.meta;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class EntityFieldMeta {
    public final String name;
    public String type;
    public String fieldKind;
    public String column;
    public String relation;
    public String expression;
    public final int lineNumber;
    public final List<String> views;
    public final Map<String, String> typeByView;

    public EntityFieldMeta(String name, String type, String fieldKind, String column, String relation, String expression, int lineNumber, List<String> views) {
        this.name = name;
        this.type = type;
        this.fieldKind = fieldKind;
        this.column = column;
        this.relation = relation;
        this.expression = expression;
        this.lineNumber = lineNumber;
        this.views = views;
        this.typeByView = new LinkedHashMap<>();
    }

    public EntityFieldMeta(String name, String type, String fieldKind, String column, String relation, String expression, int lineNumber, List<String> views, Map<String, String> typeByView) {
        this.name = name;
        this.type = type;
        this.fieldKind = fieldKind;
        this.column = column;
        this.relation = relation;
        this.expression = expression;
        this.lineNumber = lineNumber;
        this.views = views;
        this.typeByView = typeByView != null ? typeByView : new LinkedHashMap<>();
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getFieldKind() { return fieldKind; }
    public String getColumn() { return column; }
    public String getRelation() { return relation; }
    public String getExpression() { return expression; }
    public int getLineNumber() { return lineNumber; }
    public List<String> getViews() { return views; }
    public Map<String, String> getTypeByView() { return typeByView; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EntityFieldMeta)) return false;
        EntityFieldMeta that = (EntityFieldMeta) o;
        return lineNumber == that.lineNumber && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, lineNumber);
    }
}
