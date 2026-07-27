package hr.hrg.hipster.ioc;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public  @interface  HipsterContext {
    Class<?>[] dependencies() default {};
    Class<?> factory() default Void.class;

    /// Enforce stricter rules
    /// - no depndency on own context allowed
    /// @return
    boolean strict() default false;

    /// Clarifies that this bean is not a context intended for auto-generation, but allow expanding publicly available
    /// properties or zero parameter methods as dependencies. Allows for stricter enforcement during compilation
    /// and shows the difference in the intended use.
    boolean expandOnly() default false;

    /// If an implementation already exists, disable auto-build and give reference to it for documentation purposes
    Class<?> impl() default Void.class;

}