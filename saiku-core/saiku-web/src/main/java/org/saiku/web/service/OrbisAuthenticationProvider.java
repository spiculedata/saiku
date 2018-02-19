package org.saiku.web.service;

import java.net.URL;
import java.util.Scanner;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Created by brunogamacatao on 07/05/16.
 */
public class OrbisAuthenticationProvider implements AuthenticationProvider {
  private String hazelcastHost;
  private String hazelcastPort;
  
  @Override
  public Authentication authenticate(Authentication authentication) throws AuthenticationException {
    UserDetails user = createUserByUsername(authentication.getName());
    return new UsernamePasswordAuthenticationToken(user, user.getPassword(), user.getAuthorities());
  }

  @Override
  public boolean supports(Class<?> aClass) {
    return true;
  }

  private UserDetails createUserByUsername(String username) {
    String role = "ROLE_" + getUserRole(username).toUpperCase();
    return new User(username, username, true, true, true, true, AuthorityUtils.createAuthorityList("ROLE_USER", "ROLE_ORBIS", role));
  }
  
  private String getUserRole(String userId) {
    return parseUserRole(fetchUserInfoJSON(userId));
  }
  
  private String fetchUserInfoJSON(String userId) {
    if (isEmpty(this.hazelcastHost) || isEmpty(this.hazelcastPort)) {
      return DEFAULT_JSON_RESPONSE;
    }
    
    String urlStr = "http://" + this.hazelcastHost + ":" + this.hazelcastPort + "/services/user/" + userId;
    String result = DEFAULT_JSON_RESPONSE;
    
    try (Scanner scanner = new Scanner(new URL(urlStr).openStream(), "UTF-8").useDelimiter("\\A")) {
      result = scanner.hasNext() ? scanner.next() : "";
      scanner.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
    
    return result;
  }
  
  private String parseUserRole(String userInfoJSON) {
    JSONParser parser = new JSONParser();
    
    try {
      JSONObject jsonObject = (JSONObject) parser.parse(userInfoJSON);
      return (String) jsonObject.get("role");
    } catch (Exception ex) {
      ex.printStackTrace();
    }
    
    return DEFAULT_ROLE;
  }
  
  private boolean isEmpty(String str) {
    return str == null || str.trim().length() == 0;
  }

  public String getHazelcastHost() {
    return hazelcastHost;
  }

  public void setHazelcastHost(String hazelcastHost) {
    this.hazelcastHost = hazelcastHost;
  }

  public String getHazelcastPort() {
    return hazelcastPort;
  }

  public void setHazelcastPort(String hazelcastPort) {
    this.hazelcastPort = hazelcastPort;
  }
  
  private static final String DEFAULT_JSON_RESPONSE = "{\n" + 
      "    \"id\": \"1797609737\",\n" + 
      "    \"displayName\": \"unknown\",\n" + 
      "    \"lastUpdated\": \"2018-01-23 14:35\",\n" + 
      "    \"lastActive\": \"2018-01-23 14:35\",\n" + 
      "    \"role\": \"admin\",\n" + 
      "    \"systemProvidedName\": \"dcgs-test-a\",\n" + 
      "    \"email\": {\n" + 
      "        \"preferred\": \"nipr\",\n" + 
      "        \"addresses\": [\n" + 
      "            {\n" + 
      "                \"type\": \"jwics\",\n" + 
      "                \"value\": \"\"\n" + 
      "            },\n" + 
      "            {\n" + 
      "                \"type\": \"sipr\",\n" + 
      "                \"value\": \"\"\n" + 
      "            },\n" + 
      "            {\n" + 
      "                \"type\": \"nipr\",\n" + 
      "                \"value\": \"dcgs@mill.com\"\n" + 
      "            }\n" + 
      "        ]\n" + 
      "    },\n" + 
      "    \"phone\": {\n" + 
      "        \"preferred\": \"secure\",\n" + 
      "        \"numbers\": [\n" + 
      "            {\n" + 
      "                \"type\": \"comm\",\n" + 
      "                \"value\": \"\"\n" + 
      "            },\n" + 
      "            {\n" + 
      "                \"type\": \"secure\",\n" + 
      "                \"value\": \"1234567890\"\n" + 
      "            }\n" + 
      "        ]\n" + 
      "    },\n" + 
      "    \"preferences\": {}\n" + 
      "}";
  
  private static final String DEFAULT_ROLE = "admin";
}
