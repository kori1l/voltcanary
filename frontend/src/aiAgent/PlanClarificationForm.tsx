import { useEffect, useState } from "react";
import { useWorkflowDraft } from "../components/workflowDraftContext";

export function PlanClarificationForm({
  required,
  busy,
  onSubmit,
  onDirtyChange,
}: {
  required: boolean;
  busy: boolean;
  onSubmit: (answer: string) => Promise<boolean>;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [open, setOpen] = useState(required),
    [answer, setAnswer] = useState("");
  const dirty = Boolean(answer.trim());
  useWorkflowDraft(dirty, () => setAnswer(""), false);
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  return (
    <section aria-label="Уточнение плана">
      {!required ? (
        <button type="button" disabled={busy} onClick={() => setOpen((value) => !value)}>
          Уточнить план
        </button>
      ) : null}
      {open ? (
        <form
          className="form-stack"
          onSubmit={(event) => {
            event.preventDefault();
            if (!busy && answer.trim())
              void onSubmit(answer).then((ok) => {
                if (ok) setAnswer("");
              });
          }}
        >
          <label>
            Ваше уточнение
            <textarea
              value={answer}
              onChange={(event) => setAnswer(event.target.value)}
              rows={3}
              maxLength={2000}
              disabled={busy}
              required
              placeholder="Например: назови «Мой автобус», используй первый шаблон"
            />
          </label>
          <p>
            Исходный запрос и выбранные устройства сохранятся. Новый план нужно будет проверить
            перед выполнением.
          </p>
          <button className="primary-button" type="submit" disabled={busy || !answer.trim()}>
            {busy ? "Готовлю уточнённый план…" : "Продолжить с уточнением"}
          </button>
        </form>
      ) : null}
    </section>
  );
}
