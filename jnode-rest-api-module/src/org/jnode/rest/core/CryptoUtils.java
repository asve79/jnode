package org.jnode.rest.core;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class CryptoUtils {
    private CryptoUtils() {
    }

    public static String sha256(String protocolPassword) {
        MessageDigest mdEnc;
        try {
            mdEnc = MessageDigest.getInstance("SHA-256");
            mdEnc.update(protocolPassword.getBytes(), 0,
                    protocolPassword.length());
            String s = new BigInteger(1, mdEnc.digest()).toString(16);
            return "SHA256-" + s;
        } catch (NoSuchAlgorithmException e) {
            return "PLAIN-" + protocolPassword;
        }

    }


}
