package dev.dae.software.util;

import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 *
 * @author Ben
 */
public class CryptoAES {

    public static byte[] aesEncryptContent(String content, String cipherInstance, String keySeed) {
        byte[] resultContent = null;
        try {
            Security.addProvider(new BouncyCastleProvider());
            Cipher cipher = Cipher.getInstance(cipherInstance, "BC");
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            final byte[] key = sha256.digest(keySeed.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
            int ivLength = cipher.getBlockSize();
            byte[] iv = new byte[ivLength];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));
            final byte[] b = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
            byte[] encryptedByteArray = Arrays.copyOf(iv, iv.length + b.length);
            System.arraycopy(b, 0, encryptedByteArray, iv.length, b.length);
            resultContent = Base64.getEncoder().encode(encryptedByteArray);
        } catch (NoSuchProviderException | NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | IllegalBlockSizeException | BadPaddingException | InvalidAlgorithmParameterException ex) {
            Logger.getLogger(CryptoAES.class.getName()).log(Level.SEVERE, null, ex);
        }
        return resultContent;
    }
    

    public static String aesDecryptContent(String content, String cipherInstance, String keySeed) {
        String resultContent = "";
        try {
            Security.addProvider(new BouncyCastleProvider());
            Cipher cipher = Cipher.getInstance(cipherInstance, "BC");
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            final byte[] key = sha256.digest(keySeed.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
            int ivLength = cipher.getBlockSize();
            byte[] base64DecodeBytes = Base64.getDecoder().decode(content.getBytes(StandardCharsets.UTF_8));
            byte[] iv = Arrays.copyOfRange(base64DecodeBytes, 0, ivLength);
            byte[] encryptedBytes = Arrays.copyOfRange(base64DecodeBytes, ivLength, base64DecodeBytes.length);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));
            final byte[] b = cipher.doFinal(encryptedBytes);
            resultContent = new String(b, StandardCharsets.UTF_8);
        } catch (NoSuchProviderException | NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | IllegalBlockSizeException | BadPaddingException | InvalidAlgorithmParameterException ex) {
            Logger.getLogger(CryptoAES.class.getName()).log(Level.SEVERE, null, ex);
        }
        return resultContent;
    }
}
