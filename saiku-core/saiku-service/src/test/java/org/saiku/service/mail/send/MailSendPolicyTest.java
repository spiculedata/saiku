/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.mail.send;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** The default-OFF master flag: OFF unless explicitly set to "true"; env beats prop. */
public class MailSendPolicyTest {

    private static MailSendPolicy policy(Map<String, String> env, Map<String, String> prop) {
        return new MailSendPolicy(env::get, prop::get);
    }

    @Test
    public void unset_isOff_byDefault() {
        assertFalse(policy(Map.of(), Map.of()).isSendToOthersEnabled());
    }

    @Test
    public void blank_isOff() {
        Map<String, String> env = new HashMap<>();
        env.put(MailSendPolicy.ENV_KEY, "   ");
        assertFalse(policy(env, Map.of()).isSendToOthersEnabled());
    }

    @Test
    public void envTrue_enables() {
        assertTrue(policy(Map.of(MailSendPolicy.ENV_KEY, "true"), Map.of()).isSendToOthersEnabled());
    }

    @Test
    public void envTrue_caseAndSpaceInsensitive() {
        assertTrue(policy(Map.of(MailSendPolicy.ENV_KEY, "  TRUE "), Map.of()).isSendToOthersEnabled());
    }

    @Test
    public void propTrue_enables_whenEnvUnset() {
        assertTrue(policy(Map.of(), Map.of(MailSendPolicy.PROP_KEY, "true")).isSendToOthersEnabled());
    }

    @Test
    public void nonTrueValues_stayOff() {
        assertFalse(policy(Map.of(MailSendPolicy.ENV_KEY, "1"), Map.of()).isSendToOthersEnabled());
        assertFalse(policy(Map.of(MailSendPolicy.ENV_KEY, "yes"), Map.of()).isSendToOthersEnabled());
        assertFalse(policy(Map.of(MailSendPolicy.ENV_KEY, "on"), Map.of()).isSendToOthersEnabled());
        assertFalse(policy(Map.of(MailSendPolicy.ENV_KEY, "enabled"), Map.of()).isSendToOthersEnabled());
    }

    @Test
    public void envFalse_beatsPropTrue() {
        // env is consulted first and is non-blank ("false"), so prop is never read.
        assertFalse(policy(Map.of(MailSendPolicy.ENV_KEY, "false"), Map.of(MailSendPolicy.PROP_KEY, "true"))
                .isSendToOthersEnabled());
    }
}
