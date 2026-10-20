import {describe,expect,it} from "vitest";
import {transportKind} from "./transportMarker";

describe("transport marker selection",()=>{
  it("distinguishes real buses, trams and other vehicles",()=>{
    expect(transportKind("hsl-bus-0012-02619")).toBe("bus");
    expect(transportKind("hsl-tram-0040-00412")).toBe("tram");
    expect(transportKind("simulated-car")).toBe("car");
  });
});
