import type { AgentAction } from "./aiAgentApi";
import {
  NativeCollectionPreview,
  readNativeCollectionDefinition,
} from "../features/native-data/NativeCollectionPreview";

export function nativeTableDefinition(action: AgentAction) {
  const document = action.parameters.document;
  if (
    action.parameters.protocol !== "native-table-review-1" ||
    !document ||
    typeof document !== "object" ||
    Array.isArray(document)
  )
    return null;
  const value = document as Record<string, unknown>;
  if (value.language !== "VCL/1" || value.kind !== "COLLECTION") return null;
  return readNativeCollectionDefinition(value.spec);
}

export function NativeTableCreationPreview({ action }: { action: AgentAction }) {
  const definition = nativeTableDefinition(action);
  return definition ? (
    <NativeCollectionPreview definition={definition} />
  ) : (
    <p role="alert">Table preview unavailable. Refresh the saved review.</p>
  );
}
