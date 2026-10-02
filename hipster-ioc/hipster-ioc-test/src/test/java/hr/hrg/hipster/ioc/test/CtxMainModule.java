package hr.hrg.hipster.ioc.test;

/** Interface with default methods that customize bean creation,
 * should be package private to avoid exposing the methods outside the context
 *
 */
interface CtxMainModule {

    default ObjectMapper buildMapper(){
        return new ObjectMapper();
    }
}
