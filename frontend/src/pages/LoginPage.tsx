export function LoginPage() {
  return (
    <main className="login-page">
      <section className="login-panel">
        <h1>VoltCanary</h1>
        <form className="form-stack">
          <label>
            Email
            <input type="email" name="email" autoComplete="email" />
          </label>
          <label>
            Password
            <input type="password" name="password" autoComplete="current-password" />
          </label>
          <button className="primary-button" type="button">
            Sign in
          </button>
        </form>
      </section>
    </main>
  );
}
