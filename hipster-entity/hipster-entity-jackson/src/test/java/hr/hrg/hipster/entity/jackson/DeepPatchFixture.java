package hr.hrg.hipster.entity.jackson;

import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ForNameOrdinalImpl;
import hr.hrg.hipster.entity.api.Identifiable;
import hr.hrg.hipster.entity.api.ViewWriter;
import hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory;
import hr.hrg.hipster.entity.core.EEnumSet;
import hr.hrg.hipster.entity.core.EEnumSetBuilder;
import hr.hrg.hipster.entity.core.EntityUpdateTrackingArray;
import hr.hrg.hipster.entity.core.ViewChangeTracking;

import java.lang.reflect.Type;
import java.util.List;

/**
 * The deep-patch fixture for {@link EntityJacksonDeepChangeSerializerTest}: a view that holds a
 * <strong>collection of tracked views</strong>, so the deep patch has a nested level, a collection
 * index and a leaf to describe.
 *
 * <p>{@link Studio} is driven through the array-backed updatable proxy. {@link Movie} is the element
 * type and is {@link Identifiable}, which is what makes a reorder reportable; the enum
 * {@link Movie_} deliberately does not publish a {@code META} — the fixture only needs the tracking
 * surface, which the array base already provides.</p>
 */
final class DeepPatchFixture {

    private DeepPatchFixture() {
    }

    // ------------------------------------------------------------------ the element view

    /** The leaf view: two fields, a stable identity, and a mutable name. */
    interface Movie extends Identifiable<Long>, ViewWriter,
            ViewChangeTracking<Movie_, EEnumSet<Movie_>> {

        Long id();

        String name();
    }

    enum Movie_ implements FieldDef {
        id(Long.class),
        name(String.class);

        private final Class<?> javaType;

        Movie_(Class<?> javaType) {
            this.javaType = javaType;
        }

        @Override
        public Type javaType() {
            return javaType;
        }

        public static Movie_ forName(String name) {
            if (name == null) {
                return null;
            }
            return switch (name) {
                case "id" -> id;
                case "name" -> Movie_.name;
                default -> null;
            };
        }
    }

    /**
     * One element, backed by an {@link EntityUpdateTrackingArray}. The array cannot carry the
     * identity itself, so the element adds it — which is exactly the shape a generated view has.
     */
    static final class MovieRow implements Movie {
        private final EntityUpdateTrackingArray<Object, Movie_> array;

        MovieRow(long id, String name) {
            Object[] values = {id, name};
            this.array = EntityUpdateTrackingArray.create(
                    new ForNameOrdinalImpl<>(Movie_.class), Movie_.values(), values);
        }

        @Override
        public Long id() {
            return (Long) array.get(Movie_.id.ordinal());
        }

        @Override
        public String name() {
            return (String) array.get(Movie_.name.ordinal());
        }

        @Override
        public Object get(int fieldOrdinal) {
            return array.get(fieldOrdinal);
        }

        @Override
        public void set(int fieldOrdinal, Object value) {
            array.set(fieldOrdinal, value);
        }

        @Override
        public int set(String field, Object value) {
            Movie_ def = Movie_.forName(field);
            if (def == null) {
                return -1;
            }
            array.set(def.ordinal(), value);
            return def.ordinal();
        }

        @Override
        public boolean isChanged() {
            return array.isChanged();
        }

        @Override
        public EEnumSet<Movie_> changes() {
            return array.changes();
        }

        @Override
        public EEnumSetBuilder<Movie_> changesBuilder() {
            return array.changesBuilder();
        }

        @Override
        public void clearChanges() {
            array.clearChanges();
        }

        @Override
        public Object currentValue(Movie_ field) {
            return array.currentValue(field);
        }

        @Override
        public List<hr.hrg.hipster.entity.core.FieldChange<Movie_>> changedValues() {
            return array.changedValues();
        }

        @Override
        public String toString() {
            return "MovieRow[" + id() + " " + name() + "]";
        }
    }

    // ------------------------------------------------------------------ the container view

    /** The container: an identity, a title, and a collection of tracked views. */
    interface Studio extends ViewWriter, ViewChangeTracking<Studio_, EEnumSet<Studio_>> {

        Long id();

        String title();

        List<ViewChangeTracking<?, ?>> movies();
    }

    enum Studio_ implements FieldDef {
        id(Long.class),
        title(String.class),
        movies(List.class);

        private final Class<?> javaType;

        Studio_(Class<?> javaType) {
            this.javaType = javaType;
        }

        @Override
        public Type javaType() {
            return javaType;
        }

        public static Studio_ forName(String name) {
            if (name == null) {
                return null;
            }
            return switch (name) {
                case "id" -> id;
                case "title" -> Studio_.title;
                case "movies" -> movies;
                default -> null;
            };
        }

        /**
         * The view metadata the shallow change serializer needs: it resolves field names and ordinals
         * positionally, so it needs the field enum and nothing else. The creator is absent by design —
         * the fixture is materialized through the array-backed proxy, never through {@code create}.
         */
        static hr.hrg.hipster.entity.api.ViewMeta<Studio, Studio_> meta() {
            return new hr.hrg.hipster.entity.api.DefaultViewMeta<>(
                    Studio.class, Studio_.class, Studio_::forName, values -> null);
        }
    }

    /**
     * A studio over the array-backed proxy. The list is the loaded baseline: the array inherits it,
     * and {@code clearChanges()} then drops the field write that wiring it up recorded, so the deep
     * patch of a fresh studio is empty.
     */
    static final class StudioFixture {
        final java.util.ArrayList<ViewChangeTracking<?, ?>> movies;
        final EntityUpdateTrackingArray<Object, Studio_> array;
        final Studio view;

        StudioFixture(ViewChangeTracking<?, ?>... movies) {
            this.movies = new java.util.ArrayList<>(List.of(movies));
            Object[] values = {1L, "Pixar", this.movies};
            this.array = EntityUpdateTrackingArray.create(
                    new ForNameOrdinalImpl<>(Studio_.class), Studio_.values(), values);
            this.view = ArrayBackedViewProxyFactory.createUpdatable(
                    Studio.class, array, Studio_::forName);
            array.clearChanges();
        }
    }

    /** A fresh identifiable element, with its own change set cleared. */
    static MovieRow movie(long id, String name) {
        return new MovieRow(id, name);
    }

    /**
     * An element with <strong>no</strong> identity: the array-backed tracking view itself, without
     * the {@link Identifiable} mixin — which is the element type the positional fallback and its
     * diagnostic exist for. (A view that merely returns {@code null} from {@code id()} is still
     * identifiable; the distinction is the mixin, not the value.)
     *
     * <p>The concrete array type is returned rather than the tracking interface so a test can write
     * through the element's own write site — {@link EntityUpdateTrackingArray#set(int, Object)}
     * compares the value the element holds and marks the ordinal only when the two differ, which is
     * the DEC-012 rule the tracker contract now expresses.</p>
     */
    static EntityUpdateTrackingArray<Object, Movie_> anonymousMovie(String name) {
        Object[] values = {0L, name};
        return EntityUpdateTrackingArray.create(
                new ForNameOrdinalImpl<>(Movie_.class), Movie_.values(), values);
    }
}
