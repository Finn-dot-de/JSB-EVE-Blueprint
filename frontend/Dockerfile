# --- Stage 1: Base (Abhängigkeiten) ---
FROM node:26-alpine AS base
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .

# --- Stage 2: DEV (Lokale Entwicklung) ---
FROM base AS dev

# --host 0.0.0.0 ist wichtig, damit ng serve aus dem Container heraus erreichbar ist
# --poll 2000 erzwingt das File-Watching, was unter Windows/Docker oft nötig ist
CMD ["npx", "ng", "serve", "--host", "0.0.0.0", "--poll", "2000"]

# --- Stage 3: Build (Kompilieren für Prod) ---
FROM base AS build
RUN npm run build --configuration=production

# --- Stage 4: PROD (Nginx Webserver) ---
FROM nginx:alpine AS prod

# Die eigene Nginx-Konfiguration kopieren
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Das fertige Build aus Stage 3 in Nginx kopieren
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80
ENTRYPOINT ["nginx", "-g", "daemon off;"]
