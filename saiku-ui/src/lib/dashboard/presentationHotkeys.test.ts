import { describe, expect, it } from "vitest";
import { isEditableTarget, isEnterPresentationKey, isExitPresentationKey } from "./presentationHotkeys";

const key = (over: Record<string, unknown> = {}): never =>
  ({ key: "f", ctrlKey: false, metaKey: false, altKey: false, target: null, ...over }) as never;

describe("isEditableTarget", () => {
  it("is true for text-entry elements", () => {
    expect(isEditableTarget({ tagName: "INPUT" } as never)).toBe(true);
    expect(isEditableTarget({ tagName: "TEXTAREA" } as never)).toBe(true);
    expect(isEditableTarget({ tagName: "SELECT" } as never)).toBe(true);
    expect(isEditableTarget({ tagName: "DIV", isContentEditable: true } as never)).toBe(true);
  });
  it("is false for non-editable targets and nullish", () => {
    expect(isEditableTarget({ tagName: "DIV" } as never)).toBe(false);
    expect(isEditableTarget({ tagName: "BUTTON" } as never)).toBe(false);
    expect(isEditableTarget(null)).toBe(false);
    expect(isEditableTarget(undefined)).toBe(false);
  });
});

describe("isEnterPresentationKey", () => {
  it("is true for a bare f / F", () => {
    expect(isEnterPresentationKey(key({ key: "f" }))).toBe(true);
    expect(isEnterPresentationKey(key({ key: "F", shiftKey: true }))).toBe(true);
  });
  it("is false with a modifier", () => {
    expect(isEnterPresentationKey(key({ ctrlKey: true }))).toBe(false);
    expect(isEnterPresentationKey(key({ metaKey: true }))).toBe(false);
    expect(isEnterPresentationKey(key({ altKey: true }))).toBe(false);
  });
  it("is false for other keys", () => {
    expect(isEnterPresentationKey(key({ key: "g" }))).toBe(false);
    expect(isEnterPresentationKey(key({ key: "Enter" }))).toBe(false);
  });
  it("is false when typing into a text field", () => {
    expect(isEnterPresentationKey(key({ key: "f", target: { tagName: "INPUT" } }))).toBe(false);
    expect(isEnterPresentationKey(key({ key: "f", target: { tagName: "DIV", isContentEditable: true } }))).toBe(false);
  });
});

describe("isExitPresentationKey", () => {
  it("is true for Escape", () => {
    expect(isExitPresentationKey(key({ key: "Escape" }))).toBe(true);
    expect(isExitPresentationKey(key({ key: "Esc" }))).toBe(true);
  });
  it("is false otherwise", () => {
    expect(isExitPresentationKey(key({ key: "f" }))).toBe(false);
  });
});
