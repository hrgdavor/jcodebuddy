package demo;

import hr.hrg.hipster.entity.HipsterEntity;

@HipsterEntity
public interface Account extends Auditable {

    @javax.persistence.Column(name = "email_col")
    String getEmail();
}
