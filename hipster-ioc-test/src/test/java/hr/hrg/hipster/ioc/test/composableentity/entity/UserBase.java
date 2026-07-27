package hr.hrg.hipster.ioc.test.composableentity.entity;

import hr.hrg.hipster.ioc.test.composableentity.EntityRef;

public interface UserBase extends EntityRef<Long> {
    String username();
    String name();
}
