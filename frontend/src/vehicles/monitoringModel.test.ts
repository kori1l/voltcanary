import {describe,it,expect} from "vitest";
import {observedTrips,monitoringEvents,defaultMonitoring,positionsCsv,movementState} from "./monitoringModel";
import type {PositionPoint} from "./positionApi";
const point=(seconds:number,lon:number,speed:number|null=36):PositionPoint=>({recordedAt:new Date(Date.UTC(2026,0,1)+seconds*1000).toISOString(),receivedAt:"",latitude:0,longitude:lon,speedKmh:speed,headingDeg:0,quality:[]});
describe("observed vehicle monitoring",()=>{
  it("splits coverage gaps and impossible travel without adding their distance",()=>{
    const trips=observedTrips([point(0,0),point(5,0.0001),point(120,10),point(125,10.0001)],2);
    expect(trips).toHaveLength(2);expect(trips.every(trip=>trip.incomplete)).toBe(true);expect(trips.reduce((sum,trip)=>sum+trip.distanceKm,0)).toBeLessThan(0.03);
  });
  it("separates sustained idle from a later observed journey",()=>{
    const trips=observedTrips([point(0,0),point(5,0.0001),point(10,0.0001,0),point(40,0.0001,0),point(70,0.0001,0),point(75,0.0002)],1);
    expect(trips).toHaveLength(2);expect(trips[0].endIndex).toBe(4);expect(trips[0].movingSeconds).toBe(5);
  });
  it("establishes initial zone state and never invents entry across a gap",()=>{
    const settings={...defaultMonitoring,zones:[{id:"a",name:"Depot",latitude:0,longitude:0,radiusMetres:100}]};
    const events=monitoringEvents([point(0,0,0),point(30,0.001,90),point(60,0,0),point(300,0.001,90)],settings);
    expect(events.map(event=>event.type)).toEqual(["EXIT","OVERSPEED","ENTER"]);
  });
  it("preserves zero and empty optional measurements in CSV",()=>{
    expect(positionsCsv([point(0,0,0)])).toContain(",0,0,0,0,,,");
    expect(movementState(undefined)).toBe("NO_DATA");
  });
});
