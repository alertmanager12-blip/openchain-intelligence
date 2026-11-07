/* Applies the persisted theme before first paint to avoid a flash. An external file, not an inline script:
   nginx's Content-Security-Policy (script-src 'self') blocks inline scripts in production. */
try {
  var p = localStorage.getItem('oci.theme');
  var dark = p === 'dark' || ((!p || p === 'system') && matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
} catch (e) {}
