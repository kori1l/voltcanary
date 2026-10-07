export function tokenErrorMessage(error: unknown, action: string) {
  const message = error instanceof Error ? error.message : "";
  if (/expired/i.test(message)) return "This token has expired. Request a new token and try again.";
  if (/invalid|not.found|already.used/i.test(message)) return "This token is invalid or has already been used. Check the token or request a new one.";
  return `Could not ${action}. Retry or request a new token if the problem continues.`;
}
