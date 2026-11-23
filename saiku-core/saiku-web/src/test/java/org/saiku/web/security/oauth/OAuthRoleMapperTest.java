/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.oauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import org.junit.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public class OAuthRoleMapperTest {

    private static Jwt jwtWithClaims(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token-value")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .claim("sub", "alice");
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static OAuthRoleMapper mapperWith(Properties raw) {
        return new OAuthRoleMapper(OAuthResourceServerProperties.from(raw));
    }

    private static List<String> authorityNames(List<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toList());
    }

    @Test
    public void default_claim_used_verbatim_with_role_prefix() {
        Properties raw = new Properties();
        raw.setProperty(OAuthResourceServerProperties.PROP_ISSUER_URI, "https://idp.example.com");
        OAuthRoleMapper mapper = mapperWith(raw);

        Jwt jwt = jwtWithClaims(Map.of("roles", List.of("USER", "ROLE_ADMIN")));
        List<String> authorities = authorityNames(mapper.mapAuthorities(jwt));

        assertEquals(2, authorities.size());
        assertTrue("bare claim value gets ROLE_ prefixed", authorities.contains("ROLE_USER"));
        assertTrue("already-prefixed claim value is left alone", authorities.contains("ROLE_ADMIN"));
    }

    @Test
    public void configured_role_claim_supports_dotted_nested_path() {
        Properties raw = new Properties();
        raw.setProperty(OAuthResourceServerProperties.PROP_ISSUER_URI, "https://idp.example.com");
        raw.setProperty(OAuthResourceServerProperties.PROP_ROLE_CLAIM, "realm_access.roles");
        OAuthRoleMapper mapper = mapperWith(raw);

        Jwt jwt = jwtWithClaims(Map.of("realm_access", Map.of("roles", List.of("saiku-admin"))));
        List<String> authorities = authorityNames(mapper.mapAuthorities(jwt));

        assertEquals(List.of("ROLE_saiku-admin"), authorities);
    }

    @Test
    public void role_mapping_overrides_claim_value() {
        Properties raw = new Properties();
        raw.setProperty(OAuthResourceServerProperties.PROP_ISSUER_URI, "https://idp.example.com");
        raw.setProperty(OAuthResourceServerProperties.PROP_ROLE_MAPPING_PREFIX + "analyst-usa", "USA_ANALYST");
        OAuthRoleMapper mapper = mapperWith(raw);

        Jwt jwt = jwtWithClaims(Map.of("roles", List.of("analyst-usa")));
        List<String> authorities = authorityNames(mapper.mapAuthorities(jwt));

        assertEquals(List.of("ROLE_USA_ANALYST"), authorities);
    }

    @Test
    public void single_string_claim_value_is_accepted() {
        Properties raw = new Properties();
        raw.setProperty(OAuthResourceServerProperties.PROP_ISSUER_URI, "https://idp.example.com");
        OAuthRoleMapper mapper = mapperWith(raw);

        Jwt jwt = jwtWithClaims(Map.of("roles", "solo-role"));
        List<String> authorities = authorityNames(mapper.mapAuthorities(jwt));

        assertEquals(List.of("ROLE_solo-role"), authorities);
    }

    @Test
    public void missing_claim_yields_no_authorities() {
        Properties raw = new Properties();
        raw.setProperty(OAuthResourceServerProperties.PROP_ISSUER_URI, "https://idp.example.com");
        OAuthRoleMapper mapper = mapperWith(raw);

        Jwt jwt = jwtWithClaims(Map.of());
        assertTrue(mapper.mapAuthorities(jwt).isEmpty());
    }

    @Test
    public void wrong_shaped_nested_claim_yields_no_authorities_instead_of_throwing() {
        Properties raw = new Properties();
        raw.setProperty(OAuthResourceServerProperties.PROP_ISSUER_URI, "https://idp.example.com");
        raw.setProperty(OAuthResourceServerProperties.PROP_ROLE_CLAIM, "realm_access.roles");
        OAuthRoleMapper mapper = mapperWith(raw);

        // "realm_access" is a string, not a nested object — the dotted-path walk must
        // fail closed (empty list) rather than a ClassCastException.
        Jwt jwt = jwtWithClaims(Map.of("realm_access", "not-a-map"));
        assertTrue(mapper.mapAuthorities(jwt).isEmpty());
    }
}
