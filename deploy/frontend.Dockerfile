# syntax=docker/dockerfile:1.7
# OpenChain Intelligence — web frontend (build context: repository root)
FROM node:24-alpine AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build -- --configuration production \
 && mkdir -p /out \
 && if [ -d dist/frontend/browser ]; then cp -r dist/frontend/browser/. /out/; \
    else cp -r "$(find dist -type f -name index.html -exec dirname {} \; | head -n 1)"/. /out/; fi \
 && test -f /out/index.html

# Unprivileged nginx (runs as UID 101, listens on 8080)
FROM nginxinc/nginx-unprivileged:1.31-alpine
COPY deploy/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /out /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=3 CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
