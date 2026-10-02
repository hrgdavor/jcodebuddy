package hr.hrg.hipster.ioc.test.composableentity;

import java.time.Instant;

public interface IAuditable {
    String createBy();
    Instant createTs();
    String modifyBy();
    Instant modifyTs();
}

