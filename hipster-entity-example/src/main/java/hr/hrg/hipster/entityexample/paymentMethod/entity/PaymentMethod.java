package hr.hrg.hipster.entityexample.paymentMethod.entity;

import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.Identifiable;
import hr.hrg.hipster.entity.api.View;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Base payment method entity with shared transaction information.
 */
@View(addons = {PaymentMethodAuditable.class})
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
