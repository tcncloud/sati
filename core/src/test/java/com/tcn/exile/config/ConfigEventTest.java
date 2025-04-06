package com.tcn.exile.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import javax.security.auth.x500.X500Principal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

import org.bouncycastle.asn1.x509.X509Name;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.x509.X509V3CertificateGenerator;

@SuppressWarnings("deprecation") // Using deprecated X509V3CertificateGenerator for simplicity
public class ConfigEventTest {
    
    private static final Logger log = LoggerFactory.getLogger(ConfigEventTest.class);
    
    @BeforeEach
    public void setup() {
        // Add BouncyCastle as a security provider if not already added
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }
    
    @Test
    public void testGetOrgWithValidCertificateWithCN() throws Exception {
        // Generate a test certificate with CN=testorg
        String cn = "testorg";
        String certPem = generateCertificate("CN=" + cn + ", O=TestOrg, C=US");
        
        // Create ConfigEvent with the test certificate
        ConfigEvent configEvent = ConfigEvent.builder()
            .withSourceObject(this)
            .withPublicCert(certPem)
            .build();
            
        // Access org via getConfig()
        String org = configEvent.getConfig().getOrg();
        
        // Assert the correct CN was extracted
        assertEquals(cn, org, "Should extract CN from certificate");
    }
    
    @Test
    public void testGetOrgWithCertificateWithoutCN() throws Exception {
        // Generate a test certificate without CN
        String certPem = generateCertificate("O=TestOrg, C=US");
        
        // Create ConfigEvent with the test certificate
        ConfigEvent configEvent = ConfigEvent.builder()
            .withSourceObject(this)
            .withPublicCert(certPem)
            .build();
            
        // Access org via getConfig()
        String org = configEvent.getConfig().getOrg();
        
        // Assert null is returned when no CN is found
        assertNull(org, "Should return null when certificate has no CN");
    }
    
    @Test
    public void testGetOrgWithNullCertificate() {
        // Create ConfigEvent with null certificate
        ConfigEvent configEvent = ConfigEvent.builder()
            .withSourceObject(this)
            // No public cert set
            .build();
            
        // Access org via getConfig()
        String org = configEvent.getConfig().getOrg();
        
        // Assert null is returned for null certificate
        assertNull(org, "Should return null for null certificate");
    }
    
    @Test
    public void testGetOrgWithInvalidCertificate() {
        // Create ConfigEvent with invalid certificate content
        ConfigEvent configEvent = ConfigEvent.builder()
            .withSourceObject(this)
            .withPublicCert("This is not a valid certificate")
            .build();
            
        // Access org via getConfig()
        String org = configEvent.getConfig().getOrg();
        
        // Assert null is returned when certificate parsing fails
        assertNull(org, "Should return null when certificate parsing fails");
    }
    
    /**
     * Helper method to generate a test X.509 certificate with the given DN
     */
    private String generateCertificate(String dn) throws Exception {
        // Generate a key pair
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        
        // Create certificate generator
        X509V3CertificateGenerator certGen = new X509V3CertificateGenerator();
        X500Principal dnName = new X500Principal(dn);
        
        // Set certificate details
        certGen.setSerialNumber(java.math.BigInteger.valueOf(System.currentTimeMillis()));
        certGen.setSubjectDN(new X509Name(dn));
        certGen.setIssuerDN(new X509Name(dn)); // Self-signed
        certGen.setNotBefore(new Date(System.currentTimeMillis() - 50000));
        certGen.setNotAfter(new Date(System.currentTimeMillis() + 50000000));
        certGen.setPublicKey(keyPair.getPublic());
        certGen.setSignatureAlgorithm("SHA256WithRSAEncryption");
        
        // Generate certificate
        X509Certificate cert = certGen.generate(keyPair.getPrivate(), "BC");
        
        // Convert to PEM format
        return "-----BEGIN CERTIFICATE-----\n" +
               java.util.Base64.getEncoder().encodeToString(cert.getEncoded()) +
               "\n-----END CERTIFICATE-----";
    }
} 