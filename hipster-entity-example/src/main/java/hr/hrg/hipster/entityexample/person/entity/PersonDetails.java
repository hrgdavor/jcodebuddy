package hr.hrg.hipster.entityexample.person.entity;

import hr.hrg.hipster.entity.api.View;

/**
 * The {@code details} view, extended with the audit columns.
 *
 * <p>This is the example's deliberate <strong>addon</strong> use (plan.dsflash § 4.5/G6, § 9/4.1):
 * it does not extend {@code Auditable}, so the addon is the only way its columns arrive. Under
 * per-view resolution the addon's accessors are appended <em>after</em> this view's own and
 * inherited run — {@code {id, firstName, lastName, email, phoneNumber}} stay at ordinals 0–4 and
 * {@code createdAt}/{@code updatedAt} land at 5 and 6, so no existing ordinal moves (R1).</p>
 *
 * <p>The addon also inherits {@code id}/{@code firstName}/{@code lastName} from {@link Person},
 * which this view already has; those collide and are skipped with a diagnostic rather than
 * duplicated, because a duplicate enum constant would not compile.</p>
 */
@View(addons = {PersonAuditable.class})
public interface PersonDetails extends Person {
    String email();
    String phoneNumber();
}
