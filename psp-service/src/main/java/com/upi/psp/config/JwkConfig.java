package com.upi.psp.config;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Configuration
public class JwkConfig {

//    @Value("${jwt.private-key-path}")
//    private String privateKeyPath;
//
//    @Value("${jwt.public-key-path}")
//    private String publicKeyPath;

    @Bean
    public RSAPrivateKey rsaPrivateKey() throws Exception
    {

        ClassPathResource resource = new ClassPathResource("keys/private_key_pkcs8.pem");
        String content = new String(resource.getInputStream().readAllBytes());


        //String PEM headers and decode Base64
        String keyData = content
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(keyData);

        //Java Security API: reconstruct key object from raw bytes
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) kf.generatePrivate(spec);

    }

    @Bean
    public RSAPublicKey rsaPublicKey() throws Exception {

        ClassPathResource resource = new ClassPathResource("keys/public_key.pem");
        String content = new String(resource.getInputStream().readAllBytes());

        String keyData = content
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(keyData);

        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");

        return (RSAPublicKey) kf.generatePublic(spec);
    }
}