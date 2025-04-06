package com.tcn.exile.config;

import com.tcn.exile.gateclients.ConfigInterface;
import com.tcn.exile.gateclients.UnconfiguredException;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import javax.security.auth.x500.X500Principal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bouncycastle.asn1.x509.X509Name;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.x509.X509V3CertificateGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.HashMap;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.function.ThrowingSupplier;

@SuppressWarnings("deprecation") // Using deprecated X509V3CertificateGenerator for simplicity
public class ConfigTest {
    
    private static final Logger log = LoggerFactory.getLogger(ConfigTest.class);
    
    @Test
    public void testDefaultConstructor() {
        // Test default constructor creates unconfigured instance
        Config config = new Config();
        assertTrue(config.isUnconfigured());
        assertNull(config.getApiEndpoint());
        assertNull(config.getPublicCert());
        assertNull(config.getRootCert());
        assertNull(config.getPrivateKey());
    }
    
    @Test
    public void testCopyConstructor() throws Exception {
        // Create source config
        Config source = new Config();
        source.setApiEndpoint("https://api.example.com");
        source.setCertificateName("test-cert");
        source.setPublicCert(generateCertificate("CN=testorg, O=TestOrg, C=US"));
        source.setUnconfigured(false);
        
        // Test copy constructor
        Config copy = new Config(source);
        
        // Verify copied values
        assertEquals(source.getApiEndpoint(), copy.getApiEndpoint());
        assertEquals(source.getCertificateName(), copy.getCertificateName());
        assertEquals(source.getPublicCert(), copy.getPublicCert());
        assertEquals(source.isUnconfigured(), copy.isUnconfigured());
        assertEquals(source.getOrg(), copy.getOrg());
    }
    
    @Test
    public void testNullCopyConstructor() {
        // Test copy constructor with null source
        Config config = new Config((ConfigInterface)null);
        assertTrue(config.isUnconfigured());
    }
    
    @Test
    public void testGettersAndSetters() {
        // Test all getters and setters
        Config config = new Config();
        
        config.setApiEndpoint("https://api.example.com");
        assertEquals("https://api.example.com", config.getApiEndpoint());
        
        config.setCertificateName("test-cert");
        assertEquals("test-cert", config.getCertificateName());
        
        config.setCertificateDescription("Test Certificate");
        assertEquals("Test Certificate", config.getCertificateDescription());
        
        config.setFingerprintSha256("fingerprint123");
        assertEquals("fingerprint123", config.getFingerprintSha256());
        
        config.setFingerprintSha256String("fingerprint123string");
        assertEquals("fingerprint123string", config.getFingerprintSha256String());
        
        config.setPrivateKey("private-key-data");
        assertEquals("private-key-data", config.getPrivateKey());
        
        config.setRootCert("root-cert-data");
        assertEquals("root-cert-data", config.getRootCert());
        
        config.setUnconfigured(false);
        assertFalse(config.isUnconfigured());
    }
    
    @Test
    public void testGetOrgWithValidCertificate() throws Exception {
        // Test getOrg with valid certificate containing CN
        String cn = "testorg";
        String certPem = generateCertificate("CN=" + cn + ", O=TestOrg, C=US");
        
        Config config = new Config();
        config.setPublicCert(certPem);
        
        assertEquals(cn, config.getOrg());
    }
    
    @Test
    public void testGetOrgWithoutCN() throws Exception {
        // Test getOrg with certificate without CN
        String certPem = generateCertificate("O=TestOrg, C=US");
        
        Config config = new Config();
        config.setPublicCert(certPem);
        
        assertNull(config.getOrg());
    }
    
    @Test
    public void testGetOrgWithNullCertificate() {
        // Test getOrg with null certificate
        Config config = new Config();
        config.setPublicCert(null);
        
        assertNull(config.getOrg());
    }
    
    @Test
    public void testGetOrgWithInvalidCertificate() {
        // Test getOrg with invalid certificate
        Config config = new Config();
        config.setPublicCert("This is not a valid certificate");
        
        assertNull(config.getOrg());
    }
    
    @Test
    public void testGetApiHostname() throws Exception {
        // Test getApiHostname
        Config config = new Config();
        config.setApiEndpoint("https://api.example.com:8443/path");
        
        assertEquals("api.example.com", config.getApiHostname());
    }
    
    @Test
    public void testGetApiPort() throws Exception {
        // Test getApiPort with explicit port
        Config config = new Config();
        config.setApiEndpoint("https://api.example.com:8443/path");
        
        assertEquals(8443, config.getApiPort());
        
        // Test getApiPort with default port
        config.setApiEndpoint("https://api.example.com/path");
        assertEquals(443, config.getApiPort()); // Default HTTPS port
    }
    
    @Test
    public void testGetExpirationDate() throws Exception {
        // Generate cert with specific validity dates
        Date notBefore = new Date(System.currentTimeMillis() - 100000);
        Date notAfter = new Date(System.currentTimeMillis() + 100000);
        String certPem = generateCertificate("CN=test, O=Test", notBefore, notAfter);
        
        Config config = new Config();
        config.setPublicCert(certPem);
        
        // Check that the expiration date matches
        assertEquals(notAfter.getTime() / 1000, config.getExpirationDate().getTime() / 1000);
    }
    
    @Test
    public void testEquals() throws Exception {
        // Create config 1
        Config config1 = new Config();
        config1.setApiEndpoint("https://api.example.com");
        config1.setUnconfigured(false);
        
        // Create config 2 with same properties
        Config config2 = new Config();
        config2.setApiEndpoint("https://api.example.com");
        config2.setUnconfigured(false);
        
        // Test equality based on relevant fields
        assertTrue(config1.equals(config2));
        assertTrue(config2.equals(config1));
        assertEquals(config1.hashCode(), config2.hashCode());
        
        // Change something and they should no longer be equal
        config2.setApiEndpoint("https://api2.example.com");
        assertFalse(config1.equals(config2));
        assertFalse(config2.equals(config1));
        assertNotEquals(config1.hashCode(), config2.hashCode());
    }
    
    @Test
    public void testEqualsWithDifferentImplementation() throws Exception {
        // Create a config
        Config config = new Config();
        config.setApiEndpoint("https://api.example.com");
        config.setPublicCert(generateCertificate("CN=test, O=Test"));
        
        // Create a different implementation of ConfigInterface
        ConfigInterface otherImpl = new ConfigInterface() {
            @Override
            public String getCertificateDescription() { return null; }
            
            @Override
            public String getCertificateName() { return null; }
            
            @Override
            public String getApiEndpoint() { return "https://api.example.com"; }
            
            @Override
            public String getFingerprintSha256() { return null; }
            
            @Override
            public String getFingerprintSha256String() { return null; }
            
            @Override
            public boolean isUnconfigured() { return false; }
            
            @Override
            public String getRootCert() { return null; }
            
            @Override
            public String getPublicCert() { return config.getPublicCert(); }
            
            @Override
            public String getPrivateKey() { return null; }
            
            @Override
            public Date getExpirationDate() { return null; }
            
            @Override
            public String getOrg() { return "test"; }
            
            @Override
            public String getApiHostname() { return "api.example.com"; }
            
            @Override
            public int getApiPort() { return 443; }
        };
        
        // Test equality with different implementation
        assertEquals(config, otherImpl);
    }
    
    @BeforeEach
    public void setup() {
        // Add BouncyCastle as a security provider if not already added
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }
    
    /**
     * Helper method to generate a test X.509 certificate with the given DN
     */
    private String generateCertificate(String dn) throws Exception {
        return generateCertificate(dn, 
                new Date(System.currentTimeMillis() - 50000), 
                new Date(System.currentTimeMillis() + 50000000));
    }
    
    /**
     * Helper method to generate a test X.509 certificate with the given DN and validity dates
     */
    private String generateCertificate(String dn, Date notBefore, Date notAfter) throws Exception {
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
        certGen.setNotBefore(notBefore);
        certGen.setNotAfter(notAfter);
        certGen.setPublicKey(keyPair.getPublic());
        certGen.setSignatureAlgorithm("SHA256WithRSAEncryption");
        
        // Generate certificate
        X509Certificate cert = certGen.generate(keyPair.getPrivate(), "BC");
        
        // Convert to PEM format
        return "-----BEGIN CERTIFICATE-----\n" +
               java.util.Base64.getEncoder().encodeToString(cert.getEncoded()) +
               "\n-----END CERTIFICATE-----";
    }

    @Test
    public void testBase64JsonConstructor() throws Exception {
        // Create a mock ObjectMapper that will return a predefined map
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        
        // Sample JSON structure that would be encoded in base64
        String jsonStr = "{" +
                "\"ca_certificate\": \"ca-cert-data\"," +
                "\"certificate\": \"cert-data\"," +
                "\"private_key\": \"key-data\"," +
                "\"fingerprint_sha256\": \"fp-data\"," +
                "\"fingerprint_sha256_string\": \"fp-string-data\"," +
                "\"api_endpoint\": \"https://api.example.com\"," +
                "\"certificate_name\": \"test-cert\"," +
                "\"certificate_description\": \"Test Certificate\"" +
                "}";
        
        // Convert to base64
        String base64Json = Base64.getEncoder().encodeToString(jsonStr.getBytes());
        
        // Create a map that would be returned by the ObjectMapper
        Map<String, String> map = new HashMap<>();
        map.put("ca_certificate", "ca-cert-data");
        map.put("certificate", "cert-data");
        map.put("private_key", "key-data");
        map.put("fingerprint_sha256", "fp-data");
        map.put("fingerprint_sha256_string", "fp-string-data");
        map.put("api_endpoint", "https://api.example.com");
        map.put("certificate_name", "test-cert");
        map.put("certificate_description", "Test Certificate");
        
        // Set up the mock
        when(objectMapper.readValue(jsonStr.getBytes(), Map.class)).thenReturn(map);
        
        // Create the config
        Config config = Config.fromBase64Json(base64Json, objectMapper);
        
        // Verify all fields were set correctly
        assertFalse(config.isUnconfigured());
        assertEquals("ca-cert-data", config.getRootCert());
        assertEquals("cert-data", config.getPublicCert());
        assertEquals("key-data", config.getPrivateKey());
        assertEquals("fp-data", config.getFingerprintSha256());
        assertEquals("fp-string-data", config.getFingerprintSha256String());
        assertEquals("https://api.example.com", config.getApiEndpoint());
        assertEquals("test-cert", config.getCertificateName());
        assertEquals("Test Certificate", config.getCertificateDescription());
    }

    @Test
    public void testBase64JsonConstructorWithEmptyString() {
        // Test with empty string
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        assertThrows(UnconfiguredException.class, () -> Config.fromBase64Json("", objectMapper));
    }

    @Test
    public void testBase64JsonConstructorWithInvalidBase64() {
        // Test with invalid base64
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        assertThrows(UnconfiguredException.class, () -> Config.fromBase64Json("invalid-base64!", objectMapper));
    }
} 