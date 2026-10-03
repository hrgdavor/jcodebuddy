package hr.hrg.jcodebuddy.meta.rpc;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RpcMethod {
    String value();
}
