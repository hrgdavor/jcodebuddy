package hr.hrg.hipster.entityexample.paymentMethod.entity;

import hr.hrg.hipster.entity.api.BooleanOption;
import hr.hrg.hipster.entity.api.View;

@View()
public non-sealed interface CreditCardPaymentMethod extends PaymentMethod {
    default String type() { return "CREDIT_CARD";}
    String maskedCardNumber();
    String expiryDate();
    String cardType();
    String gatewayTransactionId();
}
