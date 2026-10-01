// @generated file hr.hrg.hipster.ioc.tooling.IocContextGenerator — implementation of the CtxMain context.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.hipster.ioc.test;

/**
 * Generated implementation of {@link CtxMain}.
 *
 * <p>Beans are created in dependency order by the constructor. Every accessor returns a field, so the object graph is the source you are reading.
 * The class is generated from the interface: delete it to have it regenerated, or set
 * {@code enabled:false} in the header above to take it under manual control (DEC-018).
 */
public class CtxMainImpl implements CtxMain {

    private final ObjectMapper mapper;

    public CtxMainImpl() {
        this.mapper = buildMapper();
    }

    @Override
    public ObjectMapper mapper() {
        return mapper;
    }
}
