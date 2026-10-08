/** A shared queue bounds actual reads while each entity can independently show loading. */
export class ReadLimiter {
  private running = 0;
  private queue: Array<() => void> = [];
  constructor(private readonly limit = 6) {}
  run<T>(read: () => Promise<T>, signal?: AbortSignal): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      const start = () => {
        if (signal?.aborted) { reject(new DOMException("Obsolete read", "AbortError")); this.advance(); return; }
        this.running++;
        Promise.resolve().then(read).then(resolve, reject).finally(() => { this.running--; this.advance(); });
      };
      if (this.running < this.limit) start(); else this.queue.push(start);
    });
  }
  private advance() { while (this.running < this.limit && this.queue.length) this.queue.shift()!(); }
}
