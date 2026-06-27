package com.youzheng.huicui.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;

/** 签发/解析 JWT，承载当前主体声明。 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(@Value("${huicui.jwt.secret}") String secret,
                      @Value("${huicui.jwt.ttl-seconds}") long ttlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(CurrentSubject s) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(s.accountId())
                .claim("name", s.name())
                .claim("orgId", s.orgId())
                .claim("orgType", s.orgType())
                .claim("orgName", s.orgName())
                .claim("role", s.role())
                .claim("perms", String.join(",", s.permissions()))
                .claim("dataRange", s.dataRange() == null ? null : s.dataRange().toJson())
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlSeconds * 1000))
                .signWith(key)
                .compact();
    }

    public CurrentSubject parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        String perms = c.get("perms", String.class);
        Set<String> permSet = (perms == null || perms.isBlank())
                ? Set.of() : Set.of(perms.split(","));
        return new CurrentSubject(
                c.getSubject(),
                c.get("name", String.class),
                c.get("orgId", String.class),
                c.get("orgType", String.class),
                c.get("orgName", String.class),
                c.get("role", String.class),
                permSet,
                DataRange.fromJson(c.get("dataRange", String.class)));
    }
}
