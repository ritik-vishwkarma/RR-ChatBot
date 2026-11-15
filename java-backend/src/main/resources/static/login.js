const f = document.getElementById('f'), err = document.getElementById('err');
// OAuth providers are intentionally deferred; the first implementation supports email/password.
document.querySelectorAll('[data-p]').forEach(b => b.hidden = true);
const sep = document.querySelector('.sep'); if (sep) sep.hidden = true;
async function submit(path) {
  const body = JSON.stringify({ email: f.email.value, password: f.pw.value });
  try {
    await api(path, { method: 'POST', body });
    if (path === '/auth/register') {
      err.textContent = 'Account created. Sign in with your email and password.';
      err.classList.add('success');
      return; // Registration creates an account only; it does not issue tokens.
    }
    location.href = '/chat.html';
  } catch (x) {
    err.classList.remove('success');
    err.textContent = path === '/auth/register'
      ? 'Could not create account. Use a valid email and a password of at least 12 characters.'
      : (x.message === 'Request conflicts with current state' ? 'An account with this email already exists.' : 'Wrong email or password.');
  }
}
f.onsubmit = e => { e.preventDefault(); submit('/auth/login'); };
document.getElementById('reg').onclick = () => f.reportValidity() && submit('/auth/register');
