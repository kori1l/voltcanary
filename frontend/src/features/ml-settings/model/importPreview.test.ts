import { describe, expect, it } from "vitest";
import { readPreviewRows } from "./importPreview";
describe("bounded dataset preview", () => {
  it("preserves quoted commas, escaped quotes and zero values without changing the upload", () => {
    expect(readPreviewRows('name,value\n"fridge, kitchen",0\n"a ""quoted"" name",2', "csv").rows).toEqual([{ name: "fridge, kitchen", value: "0" }, { name: 'a "quoted" name', value: "2" }]);
  });
  it("rejects ambiguous headers and malformed rows with a row number", () => {
    expect(() => readPreviewRows("x,x\n1,2", "csv")).toThrow("unique nonempty");
    expect(() => readPreviewRows("x,y\n1", "csv")).toThrow("row 2");
    expect(() => readPreviewRows("[]", "jsonl")).toThrow("line 1");
  });
  it("bounds both preview formats to twenty records", () => {
    expect(readPreviewRows("x\n" + Array.from({ length: 100 }, (_, i) => i).join("\n"), "csv").rows).toHaveLength(20);
    expect(readPreviewRows(Array.from({ length: 100 }, (_, i) => JSON.stringify({ x: i })).join("\n"), "jsonl").rows).toHaveLength(20);
  });
});
