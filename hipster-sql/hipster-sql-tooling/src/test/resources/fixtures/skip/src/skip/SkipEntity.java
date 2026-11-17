package skip;

import hr.hrg.hipster.entity.HipsterColumn;
import hr.hrg.hipster.entity.HipsterEntity;

/**
 * Discovery-only fixture: a skipped accessor is not a property (no column, no Visitor parameter).
 * It is not compiled as a generated class in the basic fixture, because an implementation still
 * would need a getter for the interface method; that distinction is handled in the next emitter pass.
 */
@HipsterEntity
public interface SkipEntity {

    String getName();

    @HipsterColumn(skip = true)
    String getInternal();
}
