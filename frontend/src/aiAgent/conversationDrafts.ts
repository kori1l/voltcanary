import {getAccessToken} from "../api/client";

export function redactConversationText(text:string) {
  return text.replace(/bearer\s+[^\s,;]+|\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+|\bsk-[A-Za-z0-9_-]{8,}|\bAIza[A-Za-z0-9_-]{30,}|\bAQ\.[A-Za-z0-9_-]{30,}|\b(?:ghp_|github_pat_)[A-Za-z0-9_]{16,}|https?:\/\/[^\s/@]+:[^\s/@]+@/gi,"[redacted]")
    .replace(/((?:password|secret|api[_ -]?key|(?:access[_ -]?|refresh[_ -]?|source[_ -]?|api[_ -]?)?token|authorization|credential)\s*["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;}]+)/gi,"$1[redacted]");
}
export function conversationDraftOwner() {
  try {
    const encoded=getAccessToken()?.split(".")[1];
    if(!encoded)return null;
    const claims=JSON.parse(atob(encoded.replace(/-/g,"+").replace(/_/g,"/")));
    const owner=JSON.stringify([claims.sub,claims.accountId??claims.sub]);
    return typeof claims.sub==="string"?`voltcanary.ai-drafts.v1.${encodeURIComponent(owner)}`:null;
  }catch{return null;}
}
export function readConversationDraft(id:string) {
  const owner=conversationDraftOwner();
  try {
    const saved=owner?JSON.parse(localStorage.getItem(`${owner}.${id}`)??"null"):null;
    if(saved&&typeof saved.text==="string"&&saved.expiresAt>Date.now())return saved.text as string;
    if(owner)localStorage.removeItem(`${owner}.${id}`);
    return "";
  }catch{return "";}
}
export function saveConversationDraft(id:string,text:string) {
  const owner=conversationDraftOwner();
  try {
    if(!owner)return false;
    if(text)localStorage.setItem(`${owner}.${id}`,JSON.stringify({text:redactConversationText(text),expiresAt:Date.now()+30*86400000}));
    else localStorage.removeItem(`${owner}.${id}`);
    return true;
  }catch{return false;}
}
