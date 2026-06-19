import { describe, it, expect } from "vitest";
import { authHeader } from "../src/lib/authHeader";

describe("authHeader", () => {
  it("returns Basic header with base64-encoded credentials", () => {
    const result = authHeader({ kind: "basic", username: "admin", password: "admin" });
    expect(result).toBe("Basic YWRtaW46YWRtaW4=");
  });

  it("returns Bearer header for OIDC credential", () => {
    const result = authHeader({ kind: "oidc", accessToken: "tok123" });
    expect(result).toBe("Bearer tok123");
  });

  it("returns null for OIDC credential with empty access token", () => {
    const result = authHeader({ kind: "oidc", accessToken: "" });
    expect(result).toBeNull();
  });

  it("returns null for null credential", () => {
    const result = authHeader(null);
    expect(result).toBeNull();
  });

  it("returns null for undefined credential", () => {
    const result = authHeader(undefined);
    expect(result).toBeNull();
  });
});
