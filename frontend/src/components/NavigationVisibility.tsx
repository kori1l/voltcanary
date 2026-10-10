import {PanelLeftOpen,PanelLeftClose} from "lucide-react";
import {createContext,useContext} from "react";
export const NavigationVisibility = createContext<{hidden:boolean;toggle:()=>void;mobile?:boolean}|null>(null);
export function SidebarToggle(){
  const navigation=useContext(NavigationVisibility);
  if(!navigation)return null;
  return <button type="button" className="ghost-button dashboard-navigation-toggle" aria-label={navigation.hidden?"Show navigation":"Hide navigation"} title={navigation.hidden?"Show navigation":"Hide navigation"} aria-expanded={!navigation.hidden} aria-controls={navigation.mobile?"console-navigation-drawer":"console-sidebar"} aria-haspopup={navigation.mobile?"dialog":undefined} onClick={navigation.toggle}>{navigation.hidden?<PanelLeftOpen size={19} aria-hidden="true"/>:<PanelLeftClose size={19} aria-hidden="true"/>}</button>;
}
