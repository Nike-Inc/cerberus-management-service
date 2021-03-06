package com.nike.cerberus.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nike.cerberus.service.ConfigService;
import com.nike.cerberus.util.UuidSupplier;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.SigningKeyResolverAdapter;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.security.Key;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A subclass of {@link SigningKeyResolverAdapter} that resolves the key used for JWT signing and
 * signature validation
 */
@Component
public class CerberusSigningKeyResolver extends SigningKeyResolverAdapter {

  private ConfigService configService;
  private final ObjectMapper objectMapper;
  private CerberusJwtKeySpec signingKey;
  private Map<String, CerberusJwtKeySpec> keyMap;
  private boolean checkKeyRotation;
  private long nextRotationTs;
  private String nextKeyId;

  // Hardcoding these for now
  private static final String DEFAULT_ALGORITHM = "HmacSHA512";
  private static final String DEFAULT_JWT_ALG_HEADER = "HS512";
  private static final int DEFAULT_MINIMUM_KEY_LENGTH_IN_BYTES = 512 / 8;

  protected final Logger log = LoggerFactory.getLogger(this.getClass());

  @Autowired
  public CerberusSigningKeyResolver(
      JwtServiceOptionalPropertyHolder jwtServiceOptionalPropertyHolder,
      ObjectMapper objectMapper,
      Optional<ConfigService> configService,
      @Value("${cerberus.auth.jwt.secret.local.autoGenerate}") boolean autoGenerate,
      @Value("${cerberus.auth.jwt.secret.local.enabled}") boolean jwtLocalEnabled,
      UuidSupplier uuidSupplier) {
    this.configService = configService.orElse(null);
    this.objectMapper = objectMapper;

    // Override key with properties, useful for local development
    if (jwtLocalEnabled) {
      if (autoGenerate) {
        log.info("Auto generating JWT secret for local development");
        SecretKey key = Keys.secretKeyFor(SignatureAlgorithm.forName(DEFAULT_JWT_ALG_HEADER));
        this.signingKey = new CerberusJwtKeySpec(key, uuidSupplier.get());
      } else {
        log.info("Using JWT secret from properties");
        if (!StringUtils.isBlank(jwtServiceOptionalPropertyHolder.jwtSecretLocalMaterial)
            && !StringUtils.isBlank(jwtServiceOptionalPropertyHolder.jwtSecretLocalKeyId)) {
          byte[] key =
              Base64.getDecoder().decode(jwtServiceOptionalPropertyHolder.jwtSecretLocalMaterial);
          this.signingKey =
              new CerberusJwtKeySpec(
                  key, DEFAULT_ALGORITHM, jwtServiceOptionalPropertyHolder.jwtSecretLocalKeyId);
        } else {
          throw new IllegalArgumentException(
              "Invalid JWT config. To resolve, either set "
                  + "cms.auth.jwt.secret.local.autoGenerate=true or provide both cms.auth.jwt.secret.local.material"
                  + " and cms.auth.jwt.secret.local.kid");
        }
      }
      rotateKeyMap(signingKey);
    } else {
      log.info("Initializing JWT key resolver using Jwt Secret from S3 bucket");
      refresh();
    }
  }

  /**
   * This 'holder' class allows optional injection of Cerberus JWT-specific properties that are only
   * necessary for local development.
   */
  @Component
  static class JwtServiceOptionalPropertyHolder {
    @Value("${cms.auth.jwt.secret.local.material: #{null}}")
    String jwtSecretLocalMaterial;

    @Value("${cms.auth.jwt.secret.local.kid: #{null}}")
    String jwtSecretLocalKeyId;
  }

  @Override
  public Key resolveSigningKey(JwsHeader jwsHeader, Claims claims) {
    // Rejects non HS512 token
    if (!StringUtils.equals(DEFAULT_JWT_ALG_HEADER, jwsHeader.getAlgorithm())) {
      throw new IllegalArgumentException("Algorithm not supported");
    }
    String keyId = jwsHeader.getKeyId();
    Key key = lookupVerificationKey(keyId);

    return key;
  }

  /**
   * Return the signing key that should be used to sign JWT. The signing key is defined as the
   * "newest active key" i.e. key with the biggest effectiveTs value and effectiveTs before now.
   *
   * @return The signing key
   */
  public CerberusJwtKeySpec resolveSigningKey() {
    if (checkKeyRotation) {
      rotateSigningKey();
      return signingKey;
    } else {
      return signingKey;
    }
  }

  /** Poll for JWT config and update key map with new data */
  public void refresh() {
    JwtSecretData jwtSecretData = getJwtSecretData();

    rotateKeyMap(jwtSecretData);
    setSigningKey(jwtSecretData);
  }

  /**
   * Poll for JWT config and validate new data
   *
   * @return JWT config
   */
  protected JwtSecretData getJwtSecretData() {
    String jwtSecretsString = configService.getJwtSecrets();
    try {
      JwtSecretData jwtSecretData = objectMapper.readValue(jwtSecretsString, JwtSecretData.class);
      validateJwtSecretData(jwtSecretData);
      return jwtSecretData;
    } catch (IOException e) {
      log.error("IOException encountered during deserialization of jwt secret data");
      throw new RuntimeException(e);
    }
  }

  /**
   * Validate {@link JwtSecretData}. Validates required fields and rejects weak keys.
   *
   * @param jwtSecretData JWT config
   */
  protected void validateJwtSecretData(JwtSecretData jwtSecretData) {
    if (jwtSecretData == null || jwtSecretData.getJwtSecrets() == null) {
      throw new IllegalArgumentException("JWT secret data cannot be null");
    }
    if (jwtSecretData.getJwtSecrets().isEmpty()) {
      throw new IllegalArgumentException("JWT secret data cannot be empty");
    }

    long minEffectiveTs = 0;

    for (JwtSecret jwtSecret : jwtSecretData.getJwtSecrets()) {
      if (jwtSecret.getSecret() == null) {
        throw new IllegalArgumentException("JWT secret cannot be null");
      }
      if (Base64.getDecoder().decode(jwtSecret.getSecret()).length
          < DEFAULT_MINIMUM_KEY_LENGTH_IN_BYTES) {
        throw new IllegalArgumentException(
            "JWT secret does NOT meet minimum length requirement of "
                + DEFAULT_MINIMUM_KEY_LENGTH_IN_BYTES);
      }
      if (StringUtils.isBlank(jwtSecret.getId())) {
        throw new IllegalArgumentException("JWT secret key ID cannot be empty");
      }
      minEffectiveTs = Math.min(minEffectiveTs, jwtSecret.getEffectiveTs());
    }

    long now = System.currentTimeMillis();
    if (now < minEffectiveTs) {
      // Prevents rotation or start up if no key is active
      throw new IllegalArgumentException("Requires at least 1 active JWT secret");
    }
  }

  /**
   * Set the signing key that should be used to sign JWT and the next signing key in line. The
   * signing key is defined as the "newest active key" i.e. key with the biggest effectiveTs value
   * and effectiveTs before now.
   *
   * @param jwtSecretData JWT config
   */
  protected void setSigningKey(JwtSecretData jwtSecretData) {
    // Find the active key
    long now = System.currentTimeMillis();
    String currentKeyId = getSigningKeyId(jwtSecretData, now);
    signingKey = keyMap.get(currentKeyId);

    // Find the next key
    List<JwtSecret> futureJwtSecrets = getFutureJwtSecrets(jwtSecretData, now);

    // Set up rotation
    if (!futureJwtSecrets.isEmpty()) {
      JwtSecret jwtSecret = futureJwtSecrets.get(0);
      checkKeyRotation = true;
      nextRotationTs = jwtSecret.getEffectiveTs();
      nextKeyId = jwtSecret.getId();
    } else {
      checkKeyRotation = false;
    }
  }

  /**
   * Get future signing keys i.e. keys with effectiveTs after now.
   *
   * @param jwtSecretData JWT config
   * @param now Timestamp of now
   * @return Future signing keys
   */
  protected List<JwtSecret> getFutureJwtSecrets(JwtSecretData jwtSecretData, long now) {
    return jwtSecretData.getJwtSecrets().stream()
        .filter(secretData -> secretData.getEffectiveTs() > now)
        .sorted(
            (secretData1, secretData2) ->
                secretData1.getEffectiveTs() - secretData2.getEffectiveTs() < 0 ? -1 : 1)
        // this puts older keys in the front of the list
        .collect(Collectors.toList());
  }

  /**
   * Get the ID of signing key that should be used to sign JWT. The signing key is defined as the
   * "newest active key" i.e. key with the biggest effectiveTs value and effectiveTs before now.
   *
   * @param jwtSecretData JWT config
   * @param now Timestamp of now in millisecond
   * @return ID of the signing key
   */
  protected String getSigningKeyId(JwtSecretData jwtSecretData, long now) {
    List<JwtSecret> sortedJwtSecrets =
        jwtSecretData.getJwtSecrets().stream()
            .filter(secretData -> secretData.getEffectiveTs() <= now)
            .sorted(
                (secretData1, secretData2) ->
                    secretData1.getEffectiveTs() - secretData2.getEffectiveTs() > 0
                        ? -1
                        : 1) // this puts newer keys in the front of the list
            .collect(Collectors.toList());
    String currentKeyId = sortedJwtSecrets.get(0).getId();
    return currentKeyId;
  }

  private void rotateKeyMap(JwtSecretData jwtSecretData) {
    ConcurrentHashMap<String, CerberusJwtKeySpec> keyMap = new ConcurrentHashMap<>();
    for (JwtSecret jwtSecret : jwtSecretData.getJwtSecrets()) {
      CerberusJwtKeySpec keySpec =
          new CerberusJwtKeySpec(
              Base64.getDecoder().decode(jwtSecret.getSecret()),
              DEFAULT_ALGORITHM,
              jwtSecret.getId());
      keyMap.put(jwtSecret.getId(), keySpec);
    }
    this.keyMap = keyMap;
  }

  private void rotateKeyMap(CerberusJwtKeySpec cerberusJwtKeySpec) {
    ConcurrentHashMap<String, CerberusJwtKeySpec> keyMap = new ConcurrentHashMap<>();
    keyMap.put(cerberusJwtKeySpec.getKid(), cerberusJwtKeySpec);
    this.keyMap = keyMap;
  }

  private Key lookupVerificationKey(String keyId) {
    if (StringUtils.isBlank(keyId)) {
      throw new IllegalArgumentException("Key ID cannot be empty");
    }
    try {
      CerberusJwtKeySpec keySpec = keyMap.get(keyId);
      if (keySpec == null) {
        throw new IllegalArgumentException("The key ID " + keyId + " is invalid or expired");
      }

      return keySpec;
    } catch (NullPointerException e) {
      throw new IllegalArgumentException("The key ID " + keyId + " is either invalid or expired");
    }
  }

  private void rotateSigningKey() {
    long now = System.currentTimeMillis();
    if (now >= nextRotationTs) {
      this.signingKey = keyMap.get(nextKeyId);
    }
    checkKeyRotation = false;
  }
}
