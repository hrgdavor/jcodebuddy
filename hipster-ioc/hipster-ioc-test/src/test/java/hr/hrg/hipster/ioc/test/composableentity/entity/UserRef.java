package hr.hrg.hipster.ioc.test.composableentity.entity;

import hr.hrg.hipster.ioc.test.composableentity.EntityRef;

public record UserRef(Long id) implements EntityRef<Long> {}
