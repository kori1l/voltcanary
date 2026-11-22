import { Link } from "react-router-dom";
import { openAssistant } from "../aiAgent/assistantWindowState";

/** The route opens the persistent application-level assistant; it does not own a second conversation. */
export function AiAgentPage() {
  return (
    <>
      <header className="page-header">
        <h1>AI-помощник</h1>
        <p>
          Задавайте вопросы и создавайте дашборды в отдельном окне. Вид и расположение можно
          изменить в его шапке.
        </p>
      </header>
      <div className="action-row">
        <button className="primary-button" onClick={openAssistant}>
          Открыть помощника
        </button>
        <Link to="/ai-agent/settings">Настройки моделей и провайдеров</Link>
      </div>
    </>
  );
}
