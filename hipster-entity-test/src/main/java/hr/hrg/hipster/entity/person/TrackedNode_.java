package hr.hrg.hipster.entity.person;

import hr.hrg.hipster.entity.api.FieldDef;

/**
 * Field definitions for the {@link TrackedNode} deep-tracking fixture.
 *
 * <p>Two fields only, so the fixture of task 6.8 stays readable at three nesting levels. No
 * {@code META} is published: the fixture is driven through the array-backed updatable proxy and
 * through a hand-written builder-side stand-in, which is the pair the parity test needs.</p>
 */
public enum TrackedNode_ implements FieldDef {
    id(Long.class),
    label(String.class);

    private final Class<?> javaType;

    TrackedNode_(Class<?> javaType) {
        this.javaType = javaType;
    }

    @Override
    public Class<?> javaType() {
        return javaType;
    }

    public static TrackedNode_ forName(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "id" -> id;
            // The parameter shadows the constant, so the constant must be qualified.
            case "label" -> TrackedNode_.label;
            default -> null;
        };
    }
}
