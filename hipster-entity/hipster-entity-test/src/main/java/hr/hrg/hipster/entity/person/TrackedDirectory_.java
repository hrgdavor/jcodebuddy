package hr.hrg.hipster.entity.person;

import hr.hrg.hipster.entity.api.FieldDef;

import java.util.List;

/**
 * Field definitions for the {@link TrackedDirectory} fixture of task 6.8.
 *
 * <p>No {@code META} is published: the fixture is driven through the array-backed updatable proxy
 * and through a hand-written builder-side stand-in, which is the same pair
 * {@code ViewChangeTrackingStateSharingTest} uses for the shallow path. The enum is still the
 * field-name contract of the view, and it is the universe the array is constructed with.</p>
 */
public enum TrackedDirectory_ implements FieldDef {
    id(Long.class),
    name(String.class),
    heads(List.class);

    private final Class<?> javaType;

    TrackedDirectory_(Class<?> javaType) {
        this.javaType = javaType;
    }

    @Override
    public Class<?> javaType() {
        return javaType;
    }

    public static TrackedDirectory_ forName(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "id" -> id;
            // The parameter shadows the constant, so the constant must be qualified here.
            case "name" -> TrackedDirectory_.name;
            case "heads" -> heads;
            default -> null;
        };
    }
}
