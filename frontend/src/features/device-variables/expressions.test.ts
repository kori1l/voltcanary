import {describe, expect, it} from "vitest";
import {affine, formatExpression, parseExpression} from "./expressions";

describe("variable expressions", () => {
  it("preserves precedence and explicit temperature conversion", () => {
    const ast = parseExpression("x * 0.1 - 0.8");
    expect(ast.op).toBe("SUBTRACT"); expect(ast.left?.op).toBe("MULTIPLY");
    expect(formatExpression(ast)).toContain("0.8");
    expect(affine(1, 0, "F", "C").left?.op).toBe("CONVERT");
  });
  it("rejects executable syntax and excessive input", () => {
    for (const expression of ["fetch(x)", "x; y", "x = 2", "x ** 2", "x[0]", "(".repeat(14) + "x" + ")".repeat(14)]) {
      expect(() => parseExpression(expression)).toThrow();
    }
    expect(() => parseExpression("x+".repeat(100) + "x")).toThrow();
  });
});
