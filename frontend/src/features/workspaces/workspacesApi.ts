import {apiRequest} from "../../api/client";
export interface Workspace {id:string;name:string;ownerUserId:string;version:number}
export interface Member {userId:string;role:string;status:string}
export interface Team {id:string;name:string;userIds:string[]}
export interface ResourceGrant {id:string;accountId:string;subjectId:string;subjectKind:string;action:string;scopeKind:string;resourceId:string}
export const listWorkspaces=(signal?:AbortSignal)=>apiRequest<Workspace[]>("/workspaces",{signal});
export const createWorkspace=(name:string)=>apiRequest<Workspace>("/workspaces",{method:"POST",body:JSON.stringify({name})});
export const listMembers=(id:string,signal?:AbortSignal)=>apiRequest<Member[]>(`/workspaces/${id}/members`,{signal});
export const saveMember=(id:string,userId:string,role:string)=>apiRequest(`/workspaces/${id}/members`,{method:"PUT",body:JSON.stringify({userId,role,reviewed:true})});
export const removeMember=(id:string,userId:string)=>apiRequest(`/workspaces/${id}/members/${userId}`,{method:"DELETE"});
export const listTeams=(id:string,signal?:AbortSignal)=>apiRequest<Team[]>(`/workspaces/${id}/teams`,{signal});
export const createTeam=(id:string,name:string,userIds:string[])=>apiRequest(`/workspaces/${id}/teams`,{method:"POST",body:JSON.stringify({name,userIds,reviewed:true})});
export const transferWorkspace=(id:string,userId:string)=>apiRequest(`/workspaces/${id}/transfer`,{method:"POST",body:JSON.stringify({userId,reviewed:true})});
export const associateAccount=(id:string,accountId:string,active:boolean)=>apiRequest(`/workspaces/${id}/resource-accounts`,{method:"POST",body:JSON.stringify({accountId,active,reviewed:true})});
export const listResourceGrants=(id:string,accountId:string,signal?:AbortSignal)=>apiRequest<ResourceGrant[]>(`/workspaces/${id}/resource-grants?${new URLSearchParams({accountId})}`,{signal});
export const saveResourceGrant=(id:string,grant:Omit<ResourceGrant,"id">)=>apiRequest(`/workspaces/${id}/resource-grants`,{method:"POST",body:JSON.stringify({grant,reviewed:true})});
export const revokeResourceGrant=(id:string,accountId:string,grantId:string)=>apiRequest(`/workspaces/${id}/resource-grants/${grantId}?${new URLSearchParams({accountId})}`,{method:"DELETE"});
export const workspaceAudit=(id:string,signal?:AbortSignal)=>apiRequest<Record<string,unknown>[]>(`/workspaces/${id}/audit`,{signal});
