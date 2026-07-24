/**
 * @vitest-environment jsdom
 */

import { afterEach, describe, expect, test } from "vitest";

import { chartPngBase64, resultToPdfBase64, stripDataUriPrefix } from "./artifacts";

afterEach(() => {
  document.body.innerHTML = "";
});

describe("chartPngBase64", () => {
  test("returns null when no .result-host canvas exists", () => {
    expect(chartPngBase64()).toBeNull();
  });

  test("returns the base64 payload (no data-uri prefix) when a chart canvas is present", () => {
    const host = document.createElement("div");
    host.className = "result-host";
    const canvas = document.createElement("canvas");
    canvas.toDataURL = () => "data:image/png;base64,AAAA";
    host.appendChild(canvas);
    document.body.appendChild(host);

    expect(chartPngBase64()).toBe("AAAA");
  });
});

describe("resultToPdfBase64", () => {
  test("resolves to null when node is null", async () => {
    await expect(resultToPdfBase64(null)).resolves.toBeNull();
  });
});

describe("stripDataUriPrefix", () => {
  test("strips a data-uri prefix, leaving only the base64 payload", () => {
    expect(stripDataUriPrefix("data:application/pdf;filename=generated.pdf;base64,QUJD")).toBe(
      "QUJD",
    );
    expect(stripDataUriPrefix("data:image/png;base64,AAAA")).toBe("AAAA");
  });

  test("returns the input unchanged when there is no comma", () => {
    expect(stripDataUriPrefix("AAAA")).toBe("AAAA");
  });
});
