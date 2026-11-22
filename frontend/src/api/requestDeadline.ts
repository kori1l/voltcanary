export class RequestDeadlineError extends Error {
  constructor() {
    super(
      "The server response was not received in time. Refresh current records before retrying changes.",
    );
    this.name = "RequestDeadlineError";
  }
}

/** Covers response body consumption as well as connection/headers; cancellation stays distinct. */
export async function withRequestDeadline<T>(
  external: AbortSignal | null | undefined,
  timeoutMs: number,
  operation: (signal: AbortSignal) => Promise<T>,
): Promise<T> {
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0)
    throw new Error("Request timeout must be a positive finite duration");
  if (external?.aborted) throw new DOMException("Obsolete read", "AbortError");
  const controller = new AbortController();
  let expired = false;
  let timer: ReturnType<typeof setTimeout>;
  let cancel: () => void;
  const interrupted = new Promise<never>((_, reject) => {
    cancel = () => {
      controller.abort();
      reject(new DOMException("Obsolete read", "AbortError"));
    };
    external?.addEventListener("abort", cancel, { once: true });
    timer = setTimeout(() => {
      expired = true;
      controller.abort();
      reject(new RequestDeadlineError());
    }, timeoutMs);
  });
  try {
    return await Promise.race([operation(controller.signal), interrupted]);
  } catch (failure) {
    if (expired) throw new RequestDeadlineError();
    throw failure;
  } finally {
    clearTimeout(timer!);
    external?.removeEventListener("abort", cancel!);
  }
}
