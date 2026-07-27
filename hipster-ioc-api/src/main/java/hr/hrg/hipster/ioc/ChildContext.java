package hr.hrg.hipster.ioc;

public interface ChildContext<P> {

    P getParent();
    void setParent(P parent);

}
