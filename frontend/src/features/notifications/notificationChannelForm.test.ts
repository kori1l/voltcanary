import { describe, expect, it } from "vitest";
import {
  createNotificationChannelRequestFromDraft,
  NotificationChannelDraft,
} from "./notificationChannelForm";

describe("notificationChannelForm", () => {
  it("builds email channel payload without provider secrets", () => {
    const request = createNotificationChannelRequestFromDraft({
      ...draft(),
      channelType: "EMAIL",
      name: "Ops email",
      emailRecipients: "ops@example.com, lead@example.com",
      emailCc: "manager@example.com",
      emailBcc: "audit@example.com",
    });

    expect(request).toEqual({
      name: "Ops email",
      channelType: "EMAIL",
      credentialsRef: null,
      enabled: true,
      config: {
        recipients: ["ops@example.com", "lead@example.com"],
        cc: ["manager@example.com"],
        bcc: ["audit@example.com"],
        fromAlias: "VoltCanary",
        subjectPrefix: "[VoltCanary]",
      },
    });
  });

  it("builds Telegram channel payload with parse mode and preview flag", () => {
    const request = createNotificationChannelRequestFromDraft({
      ...draft(),
      channelType: "TELEGRAM",
      name: "Ops Telegram",
      credentialsRef: "vault://alerts/telegram/ops",
      telegramChatId: "-1001234567890",
      telegramParseMode: "MarkdownV2",
      telegramDisableWebPagePreview: true,
    });

    expect(request).toEqual({
      name: "Ops Telegram",
      channelType: "TELEGRAM",
      credentialsRef: "vault://alerts/telegram/ops",
      enabled: true,
      config: {
        chatId: "-1001234567890",
        parseMode: "MarkdownV2",
        disableWebPagePreview: true,
      },
    });
  });

  it("rejects secret-like provider config keys before submitting", () => {
    expect(() => createNotificationChannelRequestFromDraft({
      ...draft(),
      channelType: "TELEGRAM",
      telegramChatId: "-1001234567890",
      extraConfig: { botToken: "secret-value" },
    })).toThrow(/provider secrets/i);
  });
});

function draft(): NotificationChannelDraft {
  return {
    name: "Ops webhook",
    channelType: "WEBHOOK",
    enabled: true,
    credentialsRef: "",
    webhookUrl: "https://hooks.example.test/alerts",
    emailRecipients: "",
    emailCc: "",
    emailBcc: "",
    emailFromAlias: "VoltCanary",
    emailSubjectPrefix: "[VoltCanary]",
    telegramChatId: "",
    telegramParseMode: "",
    telegramDisableWebPagePreview: true,
  };
}
