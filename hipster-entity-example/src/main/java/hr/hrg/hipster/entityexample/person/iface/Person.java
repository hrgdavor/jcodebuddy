package hr.hrg.hipster.entityexample.person.iface;

import hr.hrg.hipster.entity.api.View;

// start point V1 (record).
//#region DOCS
@View(gen = hr.hrg.hipster.entity.api.GenLevel.META)
interface Person{
    String name();
    String email();// comment
}
//#endregion
