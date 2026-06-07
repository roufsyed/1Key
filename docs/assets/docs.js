// Shared docs JS - theme toggle + active TOC link
(function () {
  const root = document.documentElement;
  const themeBtn = document.getElementById('theme-btn');
  const sun = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/></svg>';
  const moon = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z"/></svg>';

  function setTheme(t) {
    if (t === 'dark') root.setAttribute('data-theme', 'dark');
    else root.removeAttribute('data-theme');
    if (themeBtn) themeBtn.innerHTML = t === 'dark' ? sun : moon;
    localStorage.setItem('1key-theme', t);
  }
  if (themeBtn) {
    themeBtn.addEventListener('click', () =>
      setTheme(root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark'));
  }
  setTheme(localStorage.getItem('1key-theme') || 'light');

  // Active TOC link on scroll
  const tocLinks = document.querySelectorAll('.toc a[href^="#"]');
  if (tocLinks.length) {
    const sections = [...tocLinks].map(a => document.getElementById(a.getAttribute('href').slice(1))).filter(Boolean);
    const obs = new IntersectionObserver((entries) => {
      entries.forEach(e => {
        if (e.isIntersecting) {
          tocLinks.forEach(a => a.classList.toggle('active', a.getAttribute('href') === '#' + e.target.id));
        }
      });
    }, { rootMargin: '-20% 0px -70% 0px' });
    sections.forEach(s => obs.observe(s));
  }
})();
