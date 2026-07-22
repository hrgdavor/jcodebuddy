package hr.hrg.hipster.entity.tooling.meta;

public record Property(String name, String type, String fieldKind, String column, String relation, String expression, int lineNumber) {
    public Property(String name, String type) {
        this(name, type, null, null, null, null, -1);
    }

    public Property(String name, String type, int lineNumber) {
        this(name, type, null, null, null, null, lineNumber);
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getFieldKind() { return fieldKind; }
    public String getColumn() { return column; }
    public String getRelation() { return relation; }
    public String getExpression() { return expression; }
    public int getLineNumber() { return lineNumber; }
}
