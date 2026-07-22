package hr.hrg.watch2.core;

import java.io.File;

public class VerifyHash {
    public static void main(String[] args) throws Exception {
        ChecksumDatabase db = new ChecksumDatabase();
        File file = new File("../cross_verify.bin");
        String hash = db.calculateChecksum(file, false);
        System.out.println("Java Hash: " + hash);
    }
}
