package hr.hrg.hipster.entityexample.example;

import hr.hrg.hipster.entity.api.EntityBase;

import java.time.Instant;

public interface Auditable<ID> extends EntityBase<ID> {
    Instant createdAt();
    Instant updatedAt();
}
