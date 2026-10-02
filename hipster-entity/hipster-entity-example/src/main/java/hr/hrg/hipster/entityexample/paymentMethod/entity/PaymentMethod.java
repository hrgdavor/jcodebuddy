package hr.hrg.hipster.entityexample.paymentMethod.entity;

import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.Identifiable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Base payment method entity with shared transaction information.
 *
 * <p>This interface is the package's <strong>marker</strong> and deliberately carries no
 * {@code @View}: a marker is not a discovered view (G8 rule 0), the concrete subclasses in this
 * package are the views, and the hand-written {@code PaymentMethod_} enum is where the discriminator
 * field lives (plan.dsflash § 9/4.9). An earlier revision carried
 * {@code @View(addons = {PaymentMethodAuditable.class})} here; the declaration was inert — an addon
 * applies to the one view interface that declares it and to nothing else (plan.dsflash § 4.7/DR-1) —
 * so the validator reported it as {@code addon_on_non_view} and plan.dsflash § 9/4.9 removes it.
 * {@code PaymentMethodAuditable} does not extend this interface either, so it is not a view of this
 * entity and rightly gets no generated enum.</p>
 */
public sealed interface PaymentMethod extends EntityBase<Long>, Identifiable<Long> 
    permits 
    BankTransferPaymentMethod, 
    PayPalPaymentMethod, 
    CryptoPaymentMethod, 
    CreditCardPaymentMethod {

    String  type(); // Discriminator field to identify the payment method type

    String transactionId();
    BigDecimal amount();
    String currency();
    Instant timestamp();
    String status();
}
