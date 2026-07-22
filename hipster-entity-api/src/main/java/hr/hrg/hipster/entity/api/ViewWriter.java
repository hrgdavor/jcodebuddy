package hr.hrg.hipster.entity.api;

public interface ViewWriter extends ViewReader {
    int set(String field, Object value);
    void set(int fieldOrdinal, Object value);
}
