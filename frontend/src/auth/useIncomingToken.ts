import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";

export function useIncomingToken() {
  const [params, setParams] = useSearchParams();
  const incoming = params.get("token") ?? "";
  const [token, setToken] = useState(incoming);
  const [fromLink] = useState(Boolean(incoming));
  useEffect(() => {
    if (!incoming) return;
    setToken(incoming);
    const next = new URLSearchParams(params);
    next.delete("token");
    setParams(next, { replace: true });
  }, [incoming]);
  return { token, setToken, fromLink };
}
