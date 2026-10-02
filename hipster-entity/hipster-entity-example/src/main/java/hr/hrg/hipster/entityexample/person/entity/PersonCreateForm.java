package hr.hrg.hipster.entityexample.person.entity;

import hr.hrg.hipster.entity.api.View;

@View()
public interface PersonCreateForm {
    String firstName();
    String lastName();
    String email();
    String phoneNumber();
}
