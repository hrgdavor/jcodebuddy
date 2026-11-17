package demo;

import java.util.List;

import javax.persistence.Id;

import hr.hrg.hipster.entity.HipsterColumn;
import hr.hrg.hipster.entity.HipsterEntity;

@HipsterEntity(table = "user_table")
public interface User {

    @Id
    Long getId();

    @Deprecated
    @javax.persistence.Column(name = "user_name")
    List<String> getName();

    @HipsterColumn(initial = "5")
    int getAge();

    boolean hasSomething();

    boolean isActive();

    @HipsterColumn(skip = true)
    String getInternal();

    @HipsterColumn(keepRest = true)
    String getRest();
}
