package com.liushuwen.rag.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类 - 生成、解析、验证 Token
 *
 * JWT 结构：Header.Payload.Signature
 *   Header:  {"alg":"HS256","typ":"JWT"}          → Base64编码
 *   Payload: {"sub":"userId","iat":时间,"exp":时间}  → Base64编码
 *   Signature: HMAC-SHA256(Header + "." + Payload, secretKey)
 *
 * 三段用 . 拼接：eyJhbG...c.eyJzdW...Q.eyJzaG...k
 *
 * 面试考点：
 *   Q: JWT 怎么防篡改？
 *   A: Signature 用密钥对 Header+Payload 做 HMAC-SHA256 签名。
 *      改了 Payload 里的 userId，Signature 校验不通过，JwtParser 会抛异常。
 *
 *   Q: JWT 和 Session 的区别？
 *   A: Session 存服务端内存，JWT 存客户端。JWT 无状态，天然支持分布式。
 *
 *   Q: JWT 的缺点？
 *   A: 无法主动失效（签发后到过期前一直有效）。解决方案：维护黑名单或用短时效+刷新机制。
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    /**
     * 获取签名密钥
     *
     * Keys.hmacShaKeyFor() 把字符串密钥转成 SecretKey 对象
     * HS256 算法要求密钥至少 256 位（32字节），所以 secret 字符串要足够长
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 JWT Token
     *
     * @param userId 用户ID（会存入 Payload 的 subject 字段）
     * @return JWT 字符串（eyJxxx.yyy.zzz 格式）
     */
    public String generateToken(Long userId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(String.valueOf(userId))    // Payload: sub = userId
                .issuedAt(now)                       // Payload: iat = 签发时间
                .expiration(expiryDate)              // Payload: exp = 过期时间
                .signWith(getSigningKey())           // 用密钥签名
                .compact();                          // 拼接成字符串
    }

    /**
     * 从 JWT 中解析用户ID
     *
     * @param token JWT 字符串
     * @return 用户ID，解析失败返回 null
     */
    public Long getUserIdFromToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Long.parseLong(claims.getSubject());
        } catch (Exception e) {
            log.warn("JWT解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 验证 JWT 是否有效
     *
     * @param token JWT 字符串
     * @return true=有效，false=无效或过期
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            log.warn("JWT验证失败: {}", e.getMessage());
            return false;
        }
    }
}
