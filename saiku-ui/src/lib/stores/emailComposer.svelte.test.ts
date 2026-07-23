import { describe, it, expect } from "vitest";
import { emailComposer } from "./emailComposer.svelte";

describe("emailComposer", () => {
  it("starts closed and flags a requested open, consumed once", () => {
    emailComposer.consumeOpen(); // reset any prior state
    expect(emailComposer.pendingOpen).toBe(false);
    emailComposer.requestOpen();
    expect(emailComposer.pendingOpen).toBe(true);
    expect(emailComposer.consumeOpen()).toBe(true); // consuming returns true once
    expect(emailComposer.pendingOpen).toBe(false); // and clears it
    expect(emailComposer.consumeOpen()).toBe(false); // second consume is false
  });
});
