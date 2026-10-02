package hr.hrg.hipster.ioc.test;

import hr.hrg.hipster.ioc.HipsterContext;

/** Outside facing interface of a context, declaring exported beans */
@HipsterContext
public interface CtxMain extends CtxMainModule{

    ObjectMapper mapper();
}
