import type { Expression } from "./variablesApi";

export const input = (alias: string): Expression => ({op: "INPUT", alias});
export const constant = (value: number): Expression => ({op: "CONSTANT", value});
export const binary = (op: string, left: Expression, right: Expression): Expression => ({op, left, right});
export function affine(scale: number, offset: number, inputUnit: string, outputUnit: string): Expression {
  let expression = binary("MULTIPLY", input("x"), constant(scale));
  if (inputUnit !== outputUnit) expression = {op: "CONVERT", alias: outputUnit, left: expression};
  return binary("ADD", expression, constant(offset));
}
/** Local syntax assistance only. Server validation/evaluation is authoritative. */
export function parseExpression(text: string): Expression {
  if (!text.trim() || text.length > 2048) throw new Error("Use an expression of 1–2048 characters.");
  const tokens = text.match(/(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?|[A-Za-z][A-Za-z0-9_]*|[()+\-*/]|\S/g) ?? [];
  let position = 0, nodes = 0;
  function node(expression: Expression): Expression {
    if (++nodes > 64) throw new Error("Use at most 64 expression nodes.");
    return expression;
  }
  function primary(depth: number): Expression {
    if (depth > 12) throw new Error("Expression nesting exceeds 12 levels.");
    const token = tokens[position++];
    if (token === "(") {
      const result = sum(depth + 1);
      if (tokens[position++] !== ")") throw new Error("Close the parentheses.");
      return result;
    }
    if (token === "-") return node(binary("SUBTRACT", node(constant(0)), primary(depth + 1)));
    if (token && /^[A-Za-z][A-Za-z0-9_]*$/.test(token)) return node(input(token));
    if (token && Number.isFinite(Number(token)) && token !== ")") return node(constant(Number(token)));
    throw new Error("Use numbers, input aliases and + − × / with parentheses.");
  }
  function product(depth: number): Expression {
    let result = primary(depth);
    while (tokens[position] === "*" || tokens[position] === "/") {
      const op = tokens[position++] === "*" ? "MULTIPLY" : "DIVIDE";
      result = node(binary(op, result, primary(depth)));
    }
    return result;
  }
  function sum(depth: number): Expression {
    let result = product(depth);
    while (tokens[position] === "+" || tokens[position] === "-") {
      const op = tokens[position++] === "+" ? "ADD" : "SUBTRACT";
      result = node(binary(op, result, product(depth)));
    }
    return result;
  }
  const result = sum(0);
  if (position !== tokens.length) throw new Error("Unexpected token in expression.");
  return result;
}
export function formatExpression(expression: Expression): string {
  if (expression.op === "INPUT") return expression.alias ?? "";
  if (expression.op === "CONSTANT") return String(expression.value);
  if (expression.op === "CONVERT") return `convert(${formatExpression(expression.left!)}, ${expression.alias})`;
  const op: Record<string, string> = {ADD: "+", SUBTRACT: "-", MULTIPLY: "*", DIVIDE: "/"};
  return `(${formatExpression(expression.left!)} ${op[expression.op] ?? "?"} ${formatExpression(expression.right!)})`;
}
