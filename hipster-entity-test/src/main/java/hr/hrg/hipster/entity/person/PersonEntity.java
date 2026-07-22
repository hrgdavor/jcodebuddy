package hr.hrg.hipster.entity.person;

import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.Identifiable;

public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {
    // marker interface; identity/audit metadata by base contract
}
