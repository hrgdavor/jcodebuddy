package hr.hrg.hipster.entity.jackson.fixture;

import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.Identifiable;

/**
 * The sealed polymorphic root for the Jackson module's own tests (plan.dsflash § 10/5.1, § 9/4.9).
 *
 * <p>It mirrors the example's {@code paymentMethod.entity.PaymentMethod} exactly: the root is the
 * <strong>marker</strong>, it declares the discriminator accessor, and it is the one type in the
 * family whose field enum is hand-written — because only that enum can name the discriminator field
 * constant and the permitted subtypes. A generated subclass supplies its own
 * {@code discriminatorValue} and an empty subtype array (§ 4.7/DR-4's corrected reading of § 9/4.9).</p>
 */
public sealed interface Payment extends EntityBase<Long>, Identifiable<Long>
        permits Card, Wallet {

    /** The discriminator accessor every member must answer. */
    String type();
}
