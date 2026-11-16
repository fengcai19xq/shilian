FROM node:20-alpine AS build
WORKDIR /src
RUN corepack enable && npm config set registry https://registry.npmmirror.com
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY web ./
ENV VITE_API_MODE=http VITE_API_BASE=/api
RUN pnpm build

FROM nginx:1.27-alpine
COPY deploy/test/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
