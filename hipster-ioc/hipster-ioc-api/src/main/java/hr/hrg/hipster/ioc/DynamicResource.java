package hr.hrg.hipster.ioc;

import java.util.function.BiConsumer;

public interface DynamicResource<T> {

    /** Listen to changes
     * @param listener callback that will get first parameter old value, and second parameter new value
     */
    void onChange(BiConsumer<T,T> listener);

    /** Listen to changes, and also call listener immediately for use cases where you want to reuse
     * the listener for initialization and react to changes
     *
     * @param listener callback that will get first parameter old value, and second parameter new value
     */
    void onChangeAndCurrent(BiConsumer<T,T> listener);

    /** Get current value
     */
    T get();
}
