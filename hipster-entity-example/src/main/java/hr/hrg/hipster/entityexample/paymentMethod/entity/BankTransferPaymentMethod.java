package hr.hrg.hipster.entityexample.paymentMethod.entity;

import hr.hrg.hipster.entity.api.View;

@View()
public non-sealed interface BankTransferPaymentMethod extends PaymentMethod {
    default String type() {return "BANK_TRANSFER";}
    String accountNumber();
    String routingNumber();
    String bankName();
    String swiftCode();
}
