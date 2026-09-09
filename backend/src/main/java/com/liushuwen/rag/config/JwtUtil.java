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
 * JWT 工具类：生成、解析、校验 Token，使用 HS256 对称签名。
 * 【设计要点】JWT 三段结构：Header.Payload.Signature，Signature=HMAC-SHA256(Header.Payload, secret)，改 Payload 签名即不通过，天然防篡改
 * 【常见问题】JWT vs Session？——JWT 无状态存客户端、天然支持分布式，服务端无需存会话；缺点？——签发后到过期前无法主动失效，需黑名单或短时效+刷新令牌
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    /**
     * 获取 HS256 签名密钥：由配置的 secret 字符串派生。
     * 【设计要点】密钥长度：HS256 要求密钥至少 256 位（32 字节），secret 过短会被 Keys.hmacShaKeyFor 直接拒绝
     * 【常见问题】为何用对称 HS256 而非非对称 RS256？——对称实现简单、性能好但密钥须服务端独占；RS256 可公开公钥验签、适合多方
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 JWT：Payload 写入 sub=userId、iat、exp，再用密钥签名。
     * 【设计要点】无状态认证：过期时间由 jwt.expiration 注入，服务端不存会话，靠签名防篡改、靠 exp 控时效
     * 【常见问题】为什么 userId 放 subject？——sub 是 JWT 标准主题字段，语义清晰且易被解析；过期时间怎么设？——exp 取 now+expiration，超时后 JwtParser 自动拒绝
     */
    public String generateToken(Long userId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(String.valueOf(userId))    // 功能：写入 subject=userId｜要点：JWT 标准主题字段
                .issuedAt(now)                       // 功能：写入 iat 签发时间｜要点：JWT 时间戳字段
                .expiration(expiryDate)              // 功能：写入 exp 过期时间｜要点：无状态鉴权靠 exp 控时效
                .signWith(getSigningKey())           // 功能：用密钥做 HS256 签名｜要点：签名防篡改
                .compact();                          // 功能：三段用 "." 拼接成字符串
    }

    /**
     * 解析 Token 得到 userId：校验签名后读取 subject。
     * 【设计要点】失败即 null：解析/校验抛异常统一捕获返回 null，由调用方决定 401 行为，工具类不掺杂 HTTP 语义
     * 【常见问题】为何不在工具类抛异常而返回 null？——保持工具与 Web 层解耦，401 由拦截器统一返回
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
     * 校验 Token 有效性：验签名并查 exp 是否过期。
     * 【设计要点】异常即无效：任何签名错误/过期都抛异常，捕获后返回 false，供拦截器判断是否放行
     * 【常见问题】验证与解析为何分两个方法？——拦截器只需布尔结果放行，Service 才需解析出 userId，职责分离避免多余解析
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
