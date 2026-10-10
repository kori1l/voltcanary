const listeners=new Set<()=>void>();
let revision=0;
export const dashboardCatalogRevision=()=>revision;
export function subscribeDashboardCatalog(listener:()=>void){listeners.add(listener);return()=>{listeners.delete(listener);};}
export function dashboardCatalogChanged<T>(result:T):T{revision++;for(const listener of listeners)listener();return result;}
