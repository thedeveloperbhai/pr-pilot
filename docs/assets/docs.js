/* PR Pilot docs – site-wide JS */

// ── Active sidebar link via IntersectionObserver ─────────────────
(function () {
  const sidebarLinks = document.querySelectorAll('.doc-sidebar a[href^="#"]');
  if (!sidebarLinks.length) return;

  const headings = Array.from(
    document.querySelectorAll('.doc-content h2[id], .doc-content h3[id]')
  );

  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          sidebarLinks.forEach((l) => l.classList.remove('active'));
          const active = document.querySelector(
            `.doc-sidebar a[href="#${entry.target.id}"]`
          );
          if (active) active.classList.add('active');
        }
      });
    },
    { rootMargin: '-10% 0px -80% 0px' }
  );

  headings.forEach((h) => observer.observe(h));
})();

// ── Mobile nav toggle ─────────────────────────────────────────────
(function () {
  const toggle = document.getElementById('nav-toggle');
  const links  = document.querySelector('.nav-links');
  if (!toggle || !links) return;
  toggle.addEventListener('click', () => {
    links.classList.toggle('open');
  });
})();

