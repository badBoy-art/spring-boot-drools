package com.example.drools.http;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 报文加解密 / 签名工具（只用 JDK 自带的 javax.crypto，不引任何三方库）。
 *
 * 支持的算法（代码里白名单，不让配置写任意算法，避免配成 ECB 这类弱算法）：
 *   对称加密：AES_CBC(AES/CBC/PKCS5Padding)  AES_GCM(AES/GCM/NoPadding, 128bit tag)
 *   签名摘要：HMAC_SHA256  MD5
 *
 * 密钥取值规则（application.yml 的 rule-http.secrets.<ref>）：
 *   "hex:0011aabb..."    十六进制
 *   "base64:xxxx"        Base64
 *   其它                 按 UTF-8 明文（AES 要求 16/24/32 字节）
 *
 * IV 约定：
 *   配了 iv_ref → 用固定 IV（兼容对方约定，密文就是纯密文的 Base64）
 *   没配 iv_ref → 随机 IV，并把 IV 前置到密文（Base64(iv || 密文[ || tag])）
 */
public final class CryptoSupport {

    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoSupport() {
    }

    /** 解析密钥：支持 hex: / base64: 前缀，否则按 UTF-8 明文 */
    public static byte[] resolveKey(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("密钥为空（请检查 rule-http.secrets 里的配置键）");
        }
        if (raw.startsWith("hex:")) {
            return fromHex(raw.substring(4));
        }
        if (raw.startsWith("base64:")) {
            return java.util.Base64.getDecoder().decode(raw.substring(7));
        }
        return raw.getBytes(StandardCharsets.UTF_8);
    }

    /** 加密：返回 Base64 字符串 */
    public static String encrypt(String algorithm, byte[] key, byte[] iv, byte[] plain) throws Exception {
        byte[] useIv = iv != null ? iv : randomIv(algorithm);
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, algorithm, key, useIv);
        byte[] cipherText = cipher.doFinal(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (iv == null) {
            out.write(useIv);
        }
        out.write(cipherText);
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** 解密：入参是 Base64 字符串（iv 前置与否由 iv 参数决定，与加密端约定一致） */
    public static byte[] decrypt(String algorithm, byte[] key, byte[] iv, String base64) throws Exception {
        byte[] all = java.util.Base64.getDecoder().decode(base64.trim());
        byte[] useIv = iv;
        byte[] cipherText = all;
        if (iv == null) {
            int ivLen = ivLength(algorithm);
            if (all.length <= ivLen) {
                throw new IllegalArgumentException("密文长度不足，无法取出前置 IV");
            }
            useIv = new byte[ivLen];
            System.arraycopy(all, 0, useIv, 0, ivLen);
            cipherText = new byte[all.length - ivLen];
            System.arraycopy(all, ivLen, cipherText, 0, cipherText.length);
        }
        Cipher cipher = cipher(Cipher.DECRYPT_MODE, algorithm, key, useIv);
        return cipher.doFinal(cipherText);
    }

    private static Cipher cipher(int mode, String algorithm, byte[] key, byte[] iv) throws Exception {
        String transformation;
        if ("AES_GCM".equalsIgnoreCase(algorithm)) {
            transformation = "AES/GCM/NoPadding";
        } else if ("AES_CBC".equalsIgnoreCase(algorithm) || "AES".equalsIgnoreCase(algorithm)) {
            transformation = "AES/CBC/PKCS5Padding";
        } else {
            throw new IllegalArgumentException("不支持的加密算法: " + algorithm + "（白名单：AES_CBC / AES_GCM）");
        }
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        Cipher cipher = Cipher.getInstance(transformation);
        if (transformation.contains("GCM")) {
            cipher.init(mode, keySpec, new GCMParameterSpec(128, iv));
        } else {
            cipher.init(mode, keySpec, new IvParameterSpec(iv));
        }
        return cipher;
    }

    private static byte[] randomIv(String algorithm) {
        byte[] iv = new byte[ivLength(algorithm)];
        RANDOM.nextBytes(iv);
        return iv;
    }

    private static int ivLength(String algorithm) {
        return "AES_GCM".equalsIgnoreCase(algorithm) ? 12 : 16;
    }

    /** HMAC-SHA256 签名（十六进制小写） */
    public static String hmacSha256Hex(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return toHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    /** MD5 摘要（十六进制小写），有些老接口还在用 */
    public static String md5Hex(String data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return toHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static byte[] fromHex(String hex) {
        String s = hex.trim();
        if (s.length() % 2 != 0) {
            throw new IllegalArgumentException("十六进制密钥长度必须是偶数: " + s.length());
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 校验是否受支持（配置校验用） */
    public static boolean isSupportedCrypto(String algorithm) {
        return algorithm == null || algorithm.trim().isEmpty() || "NONE".equalsIgnoreCase(algorithm)
                || "AES_CBC".equalsIgnoreCase(algorithm) || "AES_GCM".equalsIgnoreCase(algorithm);
    }

    public static boolean isSupportedSign(String type) {
        return type == null || type.trim().isEmpty() || "NONE".equalsIgnoreCase(type)
                || "HMAC_SHA256".equalsIgnoreCase(type) || "MD5".equalsIgnoreCase(type);
    }
}
