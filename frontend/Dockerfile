# ---- 构建阶段 ----
FROM node:24-alpine AS build
WORKDIR /app

# 依赖版本严格以 package-lock.json 为准（与 CI 的 `npm ci` 一致），保证镜像可复现。
# NPM_REGISTRY 可在构建时覆盖：docker compose build --build-arg NPM_REGISTRY=https://registry.npmjs.org
ARG NPM_REGISTRY=https://registry.npmmirror.com
COPY package.json package-lock.json ./
RUN npm ci --registry=${NPM_REGISTRY}
COPY . .
RUN npm run build

# ---- 运行阶段 ----
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
