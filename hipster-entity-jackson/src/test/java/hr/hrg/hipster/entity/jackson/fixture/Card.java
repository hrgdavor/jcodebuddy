package hr.hrg.hipster.entity.jackson.fixture;

/** A concrete member of the {@link Payment} family, declaring its discriminator value. */
public non-sealed interface Card extends Payment {

    default String type() {
        return "CARD";
    }

    String maskedCardNumber();
}
