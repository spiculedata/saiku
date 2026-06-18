/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.servlet;

/** Pure helpers for the Office add-in manifest: public-origin derivation + manifest XML. */
public final class AddinManifest {
  private AddinManifest() {}
  public static final String ADDIN_ID = "b6e9c1a2-3d4f-4a5b-8c7d-9e0f1a2b3c4d";

  public static String deriveBaseUrl(String configPublicUrl, String forwardedProto, String forwardedHost,
                                     String requestHost, int requestPort, String requestScheme) {
    if (configPublicUrl != null && !configPublicUrl.isBlank()) return stripTrailingSlash(configPublicUrl.trim());
    if (forwardedHost != null && !forwardedHost.isBlank()) {
      String proto = (forwardedProto != null && !forwardedProto.isBlank()) ? forwardedProto.trim() : "https";
      return proto + "://" + forwardedHost.trim();
    }
    StringBuilder b = new StringBuilder(requestScheme).append("://").append(requestHost);
    if (requestPort > 0 && requestPort != 443 && requestPort != 80) b.append(':').append(requestPort);
    return b.toString();
  }

  private static String stripTrailingSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  public static String buildManifest(String baseUrl) {
    String base = stripTrailingSlash(baseUrl);
    return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <OfficeApp xmlns="http://schemas.microsoft.com/office/appforoffice/1.1"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="TaskPaneApp">
          <Id>%1$s</Id>
          <Version>1.0.0.0</Version>
          <ProviderName>Spicule</ProviderName>
          <DefaultLocale>en-US</DefaultLocale>
          <DisplayName DefaultValue="Saiku for Excel"/>
          <Description DefaultValue="Saiku analytics embedded in Excel as a task pane add-in."/>
          <IconUrl DefaultValue="%2$s/addin/assets/icon-32.png"/>
          <HighResolutionIconUrl DefaultValue="%2$s/addin/assets/icon-80.png"/>
          <SupportUrl DefaultValue="https://saiku.spicule.co.uk"/>
          <AppDomains>
            <AppDomain>%2$s</AppDomain>
          </AppDomains>
          <Hosts>
            <Host Name="Workbook"/>
          </Hosts>
          <DefaultSettings>
            <SourceLocation DefaultValue="%2$s/addin/index.html"/>
          </DefaultSettings>
          <Permissions>ReadWriteDocument</Permissions>
        </OfficeApp>
        """.formatted(ADDIN_ID, base);
  }
}
