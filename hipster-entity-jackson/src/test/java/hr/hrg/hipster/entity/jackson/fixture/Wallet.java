package hr.hrg.hipster.entity.jackson.fixture;

/** A second concrete member of the {@link Payment} family. */
public non-sealed interface Wallet extends Payment {

    default String type() {
        return "WALLET";
    }

    String walletAddress();
}
