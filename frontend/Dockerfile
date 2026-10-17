FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html /usr/share/nginx/html/
COPY css /usr/share/nginx/html/css
COPY js /usr/share/nginx/html/js
COPY data /usr/share/nginx/html/data
EXPOSE 80

COPY manifest.webmanifest sw.js offline.html /usr/share/nginx/html/
COPY icons /usr/share/nginx/html/icons
COPY assets /usr/share/nginx/html/assets
COPY favicon.ico favicon.png /usr/share/nginx/html/
