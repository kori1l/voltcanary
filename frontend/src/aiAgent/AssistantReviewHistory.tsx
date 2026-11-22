import { Undo2, Redo2 } from "lucide-react";

export function AssistantReviewHistory({
  ids,
  index,
  undoIndex,
  redoIndex,
  disabled,
  onSelect,
}: {
  ids: string[];
  index: number;
  undoIndex: number;
  redoIndex: number;
  disabled: boolean;
  onSelect: (index: number) => void;
}) {
  if (!ids.length) return null;
  return (
    <nav className="assistant-review-history" aria-label="Варианты дашборда">
      <button
        type="button"
        disabled={disabled || undoIndex < 0}
        onClick={() => onSelect(undoIndex)}
        title="Вернуть предыдущий вариант"
      >
        <Undo2 size={15} />
        Назад
      </button>
      <label>
        Вариант
        <select
          aria-label="Вариант дашборда"
          disabled={disabled}
          value={index}
          onChange={(event) => onSelect(Number(event.target.value))}
        >
          {ids.map((id, i) => (
            <option key={id} value={i}>
              {i + 1} из {ids.length}
            </option>
          ))}
        </select>
      </label>
      <button
        type="button"
        disabled={disabled || redoIndex < 0}
        onClick={() => onSelect(redoIndex)}
        title="Вернуть следующий вариант"
      >
        Вперёд
        <Redo2 size={15} />
      </button>
    </nav>
  );
}
