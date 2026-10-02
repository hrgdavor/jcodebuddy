package hr.hrg.hipster.ioc.test.composableentity;

import java.time.Instant;

public record AuditInfo(String createBy, Instant createTs, String modifyBy, Instant modifyTs) implements IAuditable{
}
