import { NotificationChannelRequest } from "../../alerts/alertsApi";

export type NotificationChannelType = "WEBHOOK" | "EMAIL" | "TELEGRAM";

export interface NotificationChannelDraft {
  name: string;
  channelType: NotificationChannelType;
  enabled: boolean;
  credentialsRef: string;
  webhookUrl: string;
  emailRecipients: string;
  emailCc: string;
  emailBcc: string;
  emailFromAlias: string;
  emailSubjectPrefix: string;
  telegramChatId: string;
  telegramParseMode: "" | "Markdown" | "MarkdownV2" | "HTML";
  telegramDisableWebPagePreview: boolean;
  extraConfig?: Record<string, unknown>;
}

const secretKeyParts = ["secret", "token", "password", "credential", "api_key", "apikey"];

export function createNotificationChannelRequestFromDraft(draft: NotificationChannelDraft): NotificationChannelRequest {
  const config = providerConfig(draft);
  if (draft.extraConfig) {
    Object.assign(config, draft.extraConfig);
  }
  if (containsSecretKey(config)) {
    throw new Error("Channel config cannot contain provider secrets; use credentials ref");
  }
  return {
    name: draft.name.trim() || defaultName(draft.channelType),
    channelType: draft.channelType,
    config,
    credentialsRef: draft.credentialsRef.trim() || null,
    enabled: draft.enabled,
  };
}

function providerConfig(draft: NotificationChannelDraft): Record<string, unknown> {
  switch (draft.channelType) {
    case "WEBHOOK":
      if (!draft.webhookUrl.trim()) {
        throw new Error("Webhook URL is required");
      }
      return { url: draft.webhookUrl.trim() };
    case "EMAIL": {
      const recipients = listValue(draft.emailRecipients);
      if (recipients.length === 0) {
        throw new Error("At least one email recipient is required");
      }
      return removeEmpty({
        recipients,
        cc: listValue(draft.emailCc),
        bcc: listValue(draft.emailBcc),
        fromAlias: draft.emailFromAlias.trim(),
        subjectPrefix: draft.emailSubjectPrefix.trim(),
      });
    }
    case "TELEGRAM":
      if (!draft.telegramChatId.trim()) {
        throw new Error("Telegram chat id is required");
      }
      return removeEmpty({
        chatId: draft.telegramChatId.trim(),
        parseMode: draft.telegramParseMode,
        disableWebPagePreview: draft.telegramDisableWebPagePreview,
      });
  }
}

function listValue(value: string) {
  return value
    .split(/[,;\n]/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function removeEmpty(config: Record<string, unknown>) {
  return Object.fromEntries(
    Object.entries(config).filter(([, value]) => {
      if (Array.isArray(value)) {
        return value.length > 0;
      }
      return value !== "";
    }),
  );
}

function containsSecretKey(value: unknown): boolean {
  if (!value || typeof value !== "object") {
    return false;
  }
  if (Array.isArray(value)) {
    return value.some(containsSecretKey);
  }
  return Object.entries(value).some(([key, nestedValue]) => {
    const normalized = key.toLowerCase();
    return secretKeyParts.some((part) => normalized.includes(part)) || containsSecretKey(nestedValue);
  });
}

function defaultName(channelType: NotificationChannelType) {
  return `${formatEnum(channelType)} channel`;
}

export function formatEnum(value: string) {
  return value
    .toLowerCase()
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}
