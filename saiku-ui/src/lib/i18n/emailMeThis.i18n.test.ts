import { describe, it, expect } from "vitest";
import enJson from "./en.json";

describe("emailMeThis i18n keys", () => {
  const emailMeThisKeys = [
    "toolbar.emailMeThis",
    "toolbar.emailMeThis.disabled",
    "modal.emailMeThis.title",
    "modal.emailMeThis.to",
    "modal.emailMeThis.subject",
    "modal.emailMeThis.send",
    "modal.emailMeThis.sending",
    "modal.emailMeThis.defaultSubject",
    "modal.emailMeThis.addressRequired",
    "modal.emailMeThis.sentTitle",
    "modal.emailMeThis.sentBody",
    "modal.emailMeThis.notConfiguredTitle",
    "modal.emailMeThis.notConfiguredBody",
    "modal.emailMeThis.badRequestTitle",
    "modal.emailMeThis.badRequestBody",
    "modal.emailMeThis.failedTitle",
    "modal.emailMeThis.failedBody",
  ];

  it("should have all 17 emailMeThis keys defined and non-empty", () => {
    for (const key of emailMeThisKeys) {
      expect(enJson).toHaveProperty(key);
      const value = (enJson as Record<string, unknown>)[key];
      expect(value).toBeTruthy();
      expect(typeof value).toBe("string");
      expect((value as string).length).toBeGreaterThan(0);
    }
  });
});
