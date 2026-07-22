package hr.hrg.hipster.entity.core;

public interface ViewChangeTracking<E extends Enum<E>, S extends EEnumSetRead<E>> {
    boolean isChanged();
    S changes();
}
