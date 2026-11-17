package hr.hrg.hipster.sql.tooling.emit;

import hr.hrg.hipster.sql.tooling.model.EntityModel;

import java.util.List;

/**
 * Emits the per-package {@code AllEntitiesInPackage} registry.
 *
 * <p>The registry is a plain class listing the generated Meta and entity classes; the wiring is the
 * explicit {@code register(ALL_META)} call in hand-written code, not a scan (DEC-019).</p>
 */
public final class AllEntitiesEmitter {

    public static final String GENERATOR = "hr.hrg.hipster.sql.tooling.HipsterSqlGenerator";

    private AllEntitiesEmitter() {
    }

    /** The package is the entities' shared package; all entities must carry the generated Meta. */
    public static String render(String packageName, List<EntityModel> entities) {
        JavaSourceWriter out = JavaSourceWriter.defaultWriter();
        out.line("// @generated file " + GENERATOR
                + " \u2014 package entity registry for " + packageName + ".");
        out.line("// {enabled:true, blockMarker: \"implicit\"}");
        if (!packageName.isEmpty()) {
            out.line("package " + packageName + ";");
            out.blank();
        }
        out.line("import hr.hrg.hipster.entity.IEntityMeta;");
        out.line("import hr.hrg.hipster.sql.HipsterSqlUtil;");
        out.blank();

        out.line("/** Explicit registry for the generated entities in this package. */");
        out.line("public class AllEntitiesInPackage {");
        out.indent();
        out.line("public static final Class<? extends IEntityMeta>[] ALL_META = HipsterSqlUtil.toArray(");
        out.indent();
        for (int i = 0; i < entities.size(); i++) {
            out.line(entities.get(i).simpleName() + "Meta.class"
                    + (i == entities.size() - 1 ? "" : ","));
        }
        out.unindent();
        out.line(");");
        out.blank();
        out.line("public static final Class[] ALL_ENTITIES = HipsterSqlUtil.toArray(");
        out.indent();
        for (int i = 0; i < entities.size(); i++) {
            out.line(entities.get(i).simpleName() + ".class"
                    + (i == entities.size() - 1 ? "" : ","));
        }
        out.unindent();
        out.line(");");
        out.unindent();
        out.line("}");
        return out.finish();
    }
}
