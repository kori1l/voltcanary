import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { sessionIdentityRevision, subscribeSession } from "../../api/client";

function initialDraft() {
  return {
    yaml: "",
    profile: "FRIDGE",
    protocol: "HTTP",
    preset: "NORMAL_OPERATION",
    message: null as string | null,
    validation: {
      valid: false,
      message: "Load and validate a simulation draft.",
    },
  };
}
type Draft = ReturnType<typeof initialDraft>;

/** Device/session scope and draft revisions fence every asynchronous completion. */
export function useSimulationDraft(deviceId: string | undefined) {
  const identity = useSyncExternalStore(
    subscribeSession,
    sessionIdentityRevision,
  );
  const key = `${identity}:${deviceId ?? ""}`;
  const scope = useRef({ key, revision: 0 });
  if (scope.current.key !== key) scope.current = { key, revision: 0 };
  const currentScope = scope.current;
  const [stored, setStored] = useState({ key, value: initialDraft() });
  const edits = useRef(0);
  const [pending, setPending] = useState<string | null>(null);
  const command = useRef<symbol | null>(null);
  const completion = useRef<Promise<void> | null>(null);

  useEffect(() => {
    command.current = null;
    setPending(null);
    return () => {
      currentScope.revision++;
    };
  }, [currentScope]);

  function ticket() {
    const revision = currentScope.revision;
    return () =>
      scope.current === currentScope &&
      currentScope.revision === revision &&
      identity === sessionIdentityRevision();
  }
  function update(patch: Partial<Draft> | ((draft: Draft) => Draft)) {
    if (
      scope.current !== currentScope ||
      identity !== sessionIdentityRevision()
    )
      return;
    setStored((previous) => {
      const draft = previous.key === key ? previous.value : initialDraft();
      return {
        key,
        value:
          typeof patch === "function" ? patch(draft) : { ...draft, ...patch },
      };
    });
  }
  function edit(patch: Partial<Draft> | ((draft: Draft) => Draft)) {
    edits.current++;
    update(patch);
  }
  function unchanged() {
    const current = ticket();
    const revision = edits.current;
    return () => current() && edits.current === revision;
  }
  async function run(
    label: string,
    operation: (current: () => boolean) => Promise<void>,
    options: { waitForActive?: boolean; propagateFailure?: boolean } = {},
  ) {
    const current = ticket();
    while (command.current) {
      if (!options.waitForActive) return;
      await completion.current;
      if (!current()) return;
    }
    const owner = Symbol(label);
    let finish!: () => void;
    completion.current = new Promise<void>((resolve) => {
      finish = resolve;
    });
    command.current = owner;
    setPending(key);
    update({ message: null });
    try {
      await operation(current);
    } catch (error) {
      if (current())
        update({
          message: error instanceof Error ? error.message : `${label} failed`,
        });
      if (options.propagateFailure) throw error;
    } finally {
      if (command.current === owner) command.current = null;
      finish();
      if (current()) setPending(null);
    }
  }
  return {
    value: stored.key === key ? stored.value : initialDraft(),
    busy: pending === key,
    update,
    edit,
    ticket,
    unchanged,
    run,
  };
}
