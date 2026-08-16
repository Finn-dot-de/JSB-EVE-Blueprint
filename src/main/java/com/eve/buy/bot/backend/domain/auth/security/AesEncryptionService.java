package com.eve.buy.bot.backend.domain.auth.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Ver- und entschluesselt die ESI-Tokens fuer die Ablage in der Datenbank.
 *
 * <p>Ein Datenbankabzug allein soll niemandem Zugriff auf die EVE-Konten geben; der
 * Schluessel liegt ausschliesslich in der Konfiguration.
 */
@Component
public class AesEncryptionService {

    @Value("${encryption.aes-key}")
    private String base64Key;

    private SecretKeySpec secretKey;
    private static final int IV_LENGTH = 16;

    /** Leitet den AES-Schluessel aus der Konfiguration ab. */
    @PostConstruct
    public void init() {
        byte[] decodedKey = Base64.getDecoder().decode(base64Key);
        if (decodedKey.length != 32) {
            throw new IllegalArgumentException("Ungültige Schlüssellänge für AES-256!");
        }
        this.secretKey = new SecretKeySpec(decodedKey, "AES");
    }

    /**
     * Verschluesselt einen Klartext fuer die Ablage in der Datenbank.
     *
     * @param plainText der zu schuetzende Text
     * @return der verschluesselte Text in Base64
     */
    public String encrypt(String plainText) {
        if (plainText == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);

            byte[] encrypted = cipher.doFinal(plainText.getBytes());

            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Fehler bei der Verschlüsselung", e);
        }
    }

    /**
     * Entschluesselt einen zuvor abgelegten Text.
     *
     * @param cipherText der verschluesselte Text in Base64
     * @return der Klartext
     */
    public String decrypt(String encryptedText) {
        if (encryptedText == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedText);

            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);

            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted);
        } catch (Exception e) {
            throw new RuntimeException("Fehler bei der Entschlüsselung", e);
        }
    }
}