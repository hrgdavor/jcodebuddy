package hr.hrg.hipster.entityexample.person.entity;

import hr.hrg.hipster.entity.core.EEnumSet;

public interface PersonUpdatableView extends PersonUpdateForm {

    PersonUpdatableView firstName(String value);
    PersonUpdatableView lastName(String value);
    PersonUpdatableView email(String value);
    PersonUpdatableView phoneNumber(String value);

    Object get(PersonUpdateForm_ field);
    boolean set(PersonUpdateForm_ field, Object value);

    EEnumSet<PersonUpdateForm_> changes();
    void clearChanges();
}
