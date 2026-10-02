package hr.hrg.hipster.entityexample.paymentMethod;

import hr.hrg.hipster.entity.api.ViewMeta;
import hr.hrg.hipster.entityexample.paymentMethod.entity.BankTransferPaymentMethod;
import hr.hrg.hipster.entityexample.paymentMethod.entity.BankTransferPaymentMethod_;
import hr.hrg.hipster.entityexample.paymentMethod.entity.CreditCardPaymentMethod;
import hr.hrg.hipster.entityexample.paymentMethod.entity.CreditCardPaymentMethod_;
import hr.hrg.hipster.entityexample.paymentMethod.entity.CryptoPaymentMethod;
import hr.hrg.hipster.entityexample.paymentMethod.entity.CryptoPaymentMethod_;
import hr.hrg.hipster.entityexample.paymentMethod.entity.PayPalPaymentMethod;
import hr.hrg.hipster.entityexample.paymentMethod.entity.PayPalPaymentMethod_;
import hr.hrg.hipster.entityexample.paymentMethod.entity.PaymentMethod;
import hr.hrg.hipster.entityexample.paymentMethod.entity.PaymentMethod_;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Polymorphic dispatch over the {@code PaymentMethod} sealed hierarchy, driven entirely by generated
 * code (plan.dsflash § 9/4.3 and § 9/4.9).
 *
 * <h3>Where every piece of the wiring comes from</h3>
 * <ul>
 *   <li>the discriminator <strong>field</strong> and the permitted subtypes come from the
 *       <em>hand-written</em> root enum {@link PaymentMethod_} — § 9/4.9's documented hand-off,
 *       because a marker is not a discovered view;</li>
 *   <li>each concrete view's discriminator <strong>value</strong> is
 *       {@code META.discriminatorValue()}, emitted by the generator from the view's own
 *       {@code default type() { return "…"; }};</li>
 *   <li>the build is a direct method call per case — no {@code Map<String, Method>}, no reflection,
 *       no {@code META-INF/services}. That is {@code AGENTS.md} § 1 / DEC-019, and it is why this
 *       {@code switch} is written out rather than discovered.</li>
 * </ul>
 *
 * <p>The one data-driven piece is the value&rarr;metadata index, and it is built from the generated
 * {@code META} constants rather than from a classpath scan, so adding a fifth payment method is a
 * compile-time concern: the switch in {@link #read} stops compiling until the new case is added.</p>
 */
public final class PaymentMethodController {

    /** The discriminator field, taken from the root enum rather than re-typed as a string. */
    public static final PaymentMethod_ DISCRIMINATOR = PaymentMethod_.META.discriminatorField();

    /** Every concrete view's metadata, indexed by the discriminator value it declares. */
    private static final Map<String, ViewMeta<? extends PaymentMethod, ?>> BY_TYPE = indexByDiscriminator();

    private static Map<String, ViewMeta<? extends PaymentMethod, ?>> indexByDiscriminator() {
        Map<String, ViewMeta<? extends PaymentMethod, ?>> byType = new LinkedHashMap<>();
        byType.put(BankTransferPaymentMethod_.META.discriminatorValue(), BankTransferPaymentMethod_.META);
        byType.put(PayPalPaymentMethod_.META.discriminatorValue(), PayPalPaymentMethod_.META);
        byType.put(CreditCardPaymentMethod_.META.discriminatorValue(), CreditCardPaymentMethod_.META);
        byType.put(CryptoPaymentMethod_.META.discriminatorValue(), CryptoPaymentMethod_.META);
        return byType;
    }

    /** The discriminator value each concrete view declares, in declaration order. */
    public static Map<String, String> discriminatorValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("BankTransferPaymentMethod", BankTransferPaymentMethod_.META.discriminatorValue());
        values.put("PayPalPaymentMethod", PayPalPaymentMethod_.META.discriminatorValue());
        values.put("CreditCardPaymentMethod", CreditCardPaymentMethod_.META.discriminatorValue());
        values.put("CryptoPaymentMethod", CryptoPaymentMethod_.META.discriminatorValue());
        return values;
    }

    /**
     * Builds the concrete view from a positional row, using the discriminator column.
     *
     * @param row the positional array, in {@link PaymentMethod_} ordinal order
     * @return the concrete view, or {@code null} when the row carries no known discriminator
     */
    public static PaymentMethod read(Object[] row) {
        Object type = row[DISCRIMINATOR.ordinal()];
        if (type == null) {
            return null;
        }
        // A direct call per case: the IDE can navigate each arm to the generated class, which is the
        // property DEC-019 requires a dispatcher to keep.
        return switch (String.valueOf(type)) {
            case "BANK_TRANSFER" -> BankTransferPaymentMethod_.META.create(row);
            case "PAYPAL" -> PayPalPaymentMethod_.META.create(row);
            case "CREDIT_CARD" -> CreditCardPaymentMethod_.META.create(row);
            case "CRYPTO" -> CryptoPaymentMethod_.META.create(row);
            default -> null;
        };
    }

    /**
     * The metadata a row's discriminator resolves to, or {@code null}.
     *
     * <p>This is the data-driven half, kept separate from {@link #read} so the navigation property of
     * that switch is not diluted. It reports the same answer the switch acts on.</p>
     */
    public static ViewMeta<? extends PaymentMethod, ?> metaFor(String type) {
        return BY_TYPE.get(type);
    }

    /** The permitted subtypes the root enum declares — the family's compile-time closure. */
    public static Class<?>[] permittedSubtypes() {
        return PaymentMethod_.META.permittedSubtypes();
    }

    private PaymentMethodController() {
    }
}
