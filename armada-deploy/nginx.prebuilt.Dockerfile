FROM nginx:1.27-alpine

RUN rm -f /etc/nginx/conf.d/default.conf

COPY nginx.conf /etc/nginx/conf.d/armada.conf
COPY stale-chunk-reload.js /usr/share/nginx/html/saas/stale-chunk-reload.js
COPY wheel-saas-pure-web/dist /usr/share/nginx/html/saas

EXPOSE 80
