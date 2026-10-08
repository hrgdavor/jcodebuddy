package hr.hrg.hipster.entityexample.person.entity;

import hr.hrg.hipster.entity.api.View;

/**
 * A read projection (a DTO) over {@link PersonSummary} — the worked example of DEC-003/DEC-007's
 * "projection + DTO marker" pattern (plan step 6.5).
 *
 * <p>The {@code dto = true} marker is what makes this interface a <em>projection contract</em> rather
 * than a materialization target, and it is what the opt-in projection pass reads: with
 * {@code --dto-projections} the generator emits {@link PersonDtoJson} beside this file, a
 * direct-call JSON writer that reads this view's own accessors and never builds the positional array
 * every other emitter walks. That is the point of a projection: a SQL row or a NoSQL document reaches
 * the response without being materialized into an entity first.</p>
 *
 * <p>Nothing here changes what the ordinary levels emit — the marker is read by one optional emitter
 * and by nothing else.</p>
 */
@View(dto = true)
public interface PersonDto extends PersonSummary {
}
