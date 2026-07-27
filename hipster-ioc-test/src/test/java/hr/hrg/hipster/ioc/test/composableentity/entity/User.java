package hr.hrg.hipster.ioc.test.composableentity.entity;

import hr.hrg.hipster.ioc.test.composableentity.AuditInfo;
import hr.hrg.hipster.ioc.test.composableentity.EntityRef;

public interface User extends EntityRef<Long> {

    AuditInfo audit();
}
