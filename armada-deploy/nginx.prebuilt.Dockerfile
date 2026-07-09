FROM nginx:1.27-alpine

RUN rm -f /etc/nginx/conf.d/default.conf

COPY nginx.conf /etc/nginx/conf.d/armada.conf
COPY stale-chunk-reload.js /usr/share/nginx/html/saas/stale-chunk-reload.js
COPY wheel-saas-pure-web/dist /usr/share/nginx/html/saas
COPY render-platform-config.sh /docker-entrypoint.d/10-render-platform-config.sh

ENV PLATFORM_CONFIG_ROOT=/usr/share/nginx/html/saas

RUN chmod +x /docker-entrypoint.d/10-render-platform-config.sh

EXPOSE 80
