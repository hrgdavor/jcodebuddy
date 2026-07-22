package hr.hrg.hipster.entity.api;

public class ForNameOrdinalImpl<E extends Enum<E>> implements ForNameOrdinal {

    private final Class<E> enumClass;

    public ForNameOrdinalImpl(Class<E> enumClass) {
        this.enumClass = enumClass;
    }

    @Override
    public int forNameOrdinal(String fieldName) {
        try {
            return Enum.valueOf(enumClass, fieldName).ordinal();
        } catch (IllegalArgumentException e) {
            return -1; // field not found
        }
    }
}   
