import { useEffect, useState } from "react";

export function DurationInput({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const parsed = parseDurationInput(value);
  const [amount, setAmount] = useState(parsed.amount);
  const [unit, setUnit] = useState(parsed.unit);

  useEffect(() => {
    const next = parseDurationInput(value);
    setAmount(next.amount);
    setUnit(next.unit);
  }, [value]);

  function emit(nextAmount: string, nextUnit: string) {
    setAmount(nextAmount);
    setUnit(nextUnit);
    onChange(nextAmount === "" ? "" : `${nextAmount}${nextUnit}`);
  }

  return (
    <div className="duration-input">
      <input
        min="0"
        step="1"
        type="number"
        value={amount}
        onChange={(event) => emit(event.target.value, unit)}
      />
      <select value={unit} onChange={(event) => emit(amount, event.target.value)}>
        <option value="s">sec</option>
        <option value="m">min</option>
        <option value="h">hr</option>
      </select>
    </div>
  );
}

export function parseDurationInput(value: string) {
  const match = value.trim().match(/^(\d+(?:\.\d+)?)(ms|s|m|h)?$/);
  return {
    amount: match?.[1] ?? "",
    unit: match?.[2] && match[2] !== "ms" ? match[2] : "s",
  };
}
