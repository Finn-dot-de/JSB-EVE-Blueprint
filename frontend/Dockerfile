# --- Stage 1: Base (Abhängigkeiten) ---
#
# Hier steht bewusst KEIN "COPY . .". Diese Stufe enthält nur, was sich selten
# ändert - dadurch überlebt der npm-ci-Layer jede Quelltextänderung. Er kostet
# rund 60 Sekunden, wenn er fällt; alles andere zusammen keine 10.
FROM node:26-alpine AS base
WORKDIR /app
COPY package*.json ./
RUN npm ci

# --- Stage 2: DEV (Lokale Entwicklung) ---
FROM base AS dev

# Der Quellcode kommt zur Laufzeit per Bind-Mount (./frontend:/app in der
# docker-compose.yaml), node_modules aus dem anonymen Volume - und das wird
# aus dieser Stufe befüllt, also sauber aus npm ci statt aus einer Mischung
# mit den Windows-Modulen des Hosts.
#
# --host 0.0.0.0 ist wichtig, damit ng serve aus dem Container heraus erreichbar ist
# --poll 2000 erzwingt das File-Watching, was unter Windows/Docker oft nötig ist
CMD ["npx", "ng", "serve", "--host", "0.0.0.0", "--poll", "2000"]

# --- Stage 3: Build (Kompilieren für Prod) ---
#
# Erst hier kommt der Quellcode dazu: Diese Stufe darf bei jeder Änderung neu
# laufen, die Stufe darüber nicht.
FROM base AS build
COPY . .

# Ohne Flag, und das ist Absicht: "npm run build --configuration=production"
# reicht das Flag nicht an ng durch, npm schluckt es ("Unknown cli config").
# Produktion kommt aus defaultConfiguration in der angular.json. Wer es
# ausdrücklich haben will, nimmt "npx ng build --configuration=production".
RUN npm run build

# --- Stage 4: PROD (Nginx Webserver) ---
FROM nginx:alpine AS prod

# Die eigene Nginx-Konfiguration kopieren
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Das fertige Build aus Stage 3 in Nginx kopieren
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80
ENTRYPOINT ["nginx", "-g", "daemon off;"]
