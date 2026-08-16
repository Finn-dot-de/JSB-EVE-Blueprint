export const environment = {
  production: true,
  // Relativ, damit der Browser über die eigene Domain geht und Nginx
  // (frontend/nginx.conf) /api/ an den Backend-Container weiterreicht.
  apiUrl: '/api'
};
