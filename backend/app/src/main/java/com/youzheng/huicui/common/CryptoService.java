package com.youzheng.huicui.common;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 对称加解密（AES-256-GCM）。目前唯一用途：三方通道密钥落库（integration_config.secrets·V936）。
 *
 * 【主密钥】huicui.crypto.master-key（环境变量 HUICUI_CRYPTO_KEY）。派生：SHA-256(masterKey) → 32 字节。
 *   **未配置 → encrypt 直接抛 409**：宁可存不进去，也不要把三方 key 明文躺在库里。
 *   dev profile 有内置串（application-dev.yml），prod 必须注入——ProdEnvironmentGuard 会拦。
 *
 * 【密文格式】base64(iv(12B) || ciphertext || tag(16B))。GCM 自带完整性校验：
 *   换了主密钥 / 密文被改 → decrypt 抛 AEADBadTagException，我们兜成「读不出来」而非 5xx。
 *
 * 【为什么不用可逆的 Base64/异或】那不是加密，是编码。库被拖走时它等于明文。
 */
@Service
public class CryptoService {

    private static final int IV_BYTES = 12;          // GCM 推荐 96-bit IV
    private static final int TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    private final byte[] key;                        // null = 未配置主密钥

    public CryptoService(@Value("${huicui.crypto.master-key:}") String masterKey) {
        this.key = (masterKey == null || masterKey.isBlank()) ? null : derive(masterKey);
    }

    /** 主密钥是否就绪。前端据此提示「未配置 HUICUI_CRYPTO_KEY，无法在后台保存密钥」。 */
    public boolean isReady() {
        return key != null;
    }

    /** 明文 → base64(iv||ct||tag)。主密钥未配置 → 409（写侧 fail-closed）。 */
    public String encrypt(String plain) {
        if (key == null) {
            throw new ApiException(BizError.STATE_409,
                    "未配置主密钥 HUICUI_CRYPTO_KEY，无法保存三方密钥（密钥必须加密落库）");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /**
     * base64(iv||ct||tag) → 明文。**解不出来返回 null 而非抛异常**——换主密钥/密文损坏时，
     * 调用方（发短信、出存证）应回落 yml 或视作未配置，而不是把整条业务打成 5xx。
     */
    public String decrypt(String cipherText) {
        if (key == null || cipherText == null || cipherText.isBlank()) return null;
        try {
            byte[] all = Base64.getDecoder().decode(cipherText);
            if (all.length <= IV_BYTES) return null;
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;                              // 主密钥换过 / 密文被改 → 视作未配置
        }
    }

    /** 掩码：只留尾 4 位（长度 <4 时全掩）。读接口只回它，明文永不出接口。 */
    public static String mask(String plain) {
        if (plain == null || plain.isBlank()) return null;
        String t = plain.trim();
        return t.length() <= 4 ? "****" : "****" + t.substring(t.length() - 4);
    }

    private static byte[] derive(String masterKey) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(masterKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("派生主密钥失败", e);
        }
    }
}
