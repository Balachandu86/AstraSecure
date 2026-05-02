# Reverse Proxy Setup (Nginx / Caddy)

Running Node.js directly on port 443 requires root privileges and means a crash in Node.js exposes your TLS key. The recommended production pattern is:

```
Internet → :443 (Nginx/Caddy, TLS termination) → :3000 (Node.js, plain HTTP)
```

This way:
- TLS is handled by a battle-tested web server (Nginx) or automatic-TLS daemon (Caddy)
- Node.js runs as an unprivileged user on port 3000
- WebSocket (`wss://`) upgrade is proxied transparently

---

## Option A — Caddy (simplest, recommended)

Caddy handles TLS certificate provisioning and renewal automatically with zero configuration. It uses Let's Encrypt by default and renews before expiry.

### Install Caddy

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy
```

### Configure Caddy

```bash
sudo nano /etc/caddy/Caddyfile
```

```caddyfile
astra.yourdomain.com {
    reverse_proxy localhost:3000

    # WebSocket support is automatic — Caddy proxies Upgrade headers by default
}
```

That is the complete configuration. Caddy:
- Obtains and renews a Let's Encrypt certificate automatically
- Proxies all HTTP and WebSocket traffic to port 3000
- Enforces HTTPS, redirects HTTP to HTTPS

```bash
sudo systemctl enable caddy
sudo systemctl restart caddy

# Check logs
sudo journalctl -u caddy -f
```

Update UFW — Caddy needs ports 80 (for ACME challenges) and 443:

```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
# Port 3000 should NOT be publicly accessible when behind a reverse proxy
sudo ufw delete allow 3000/tcp
```

Update the Android build config to use `https://astra.yourdomain.com`.

---

## Option B — Nginx

### Install Nginx

```bash
sudo apt install -y nginx
```

### Configure Nginx

```bash
sudo nano /etc/nginx/sites-available/astrasecure
```

```nginx
server {
    listen 80;
    server_name astra.yourdomain.com;

    # Redirect all HTTP to HTTPS
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name astra.yourdomain.com;

    ssl_certificate     /etc/letsencrypt/live/astra.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/astra.yourdomain.com/privkey.pem;

    # Modern TLS config (TLS 1.2 minimum, TLS 1.3 preferred)
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:DHE-RSA-AES128-GCM-SHA256;
    ssl_prefer_server_ciphers off;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;

    # Security headers
    add_header Strict-Transport-Security "max-age=63072000" always;

    # HTTP/REST proxy
    location / {
        proxy_pass         http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
    }

    # WebSocket proxy — must upgrade the connection
    location /v1/websocket {
        proxy_pass         http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header   Upgrade    $http_upgrade;
        proxy_set_header   Connection "upgrade";
        proxy_set_header   Host       $host;
        proxy_read_timeout 86400s;   # Keep WebSocket alive for up to 24h
        proxy_send_timeout 86400s;
    }
}
```

Enable the config:

```bash
sudo ln -s /etc/nginx/sites-available/astrasecure /etc/nginx/sites-enabled/
sudo nginx -t          # test config syntax
sudo systemctl reload nginx
sudo systemctl enable nginx
```

### Obtain a certificate with Certbot for Nginx

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d astra.yourdomain.com
```

Certbot modifies the Nginx config to add the certificate paths and auto-renewal. Test renewal:

```bash
sudo certbot renew --dry-run
```

Update UFW:

```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw delete allow 3000/tcp   # block direct access to Node.js port
```

---

## Keep the Node.js server on port 3000 (no TLS in .env)

When using Nginx or Caddy as a reverse proxy, **remove** `TLS_CERT` and `TLS_KEY` from `.env` so Node.js stays on plain HTTP — TLS is handled entirely by the proxy:

```env
DATABASE_URL=postgres://astra_user:password@localhost:5432/astrasecure
JWT_SECRET=your-secret
PORT=3000
# TLS_CERT and TLS_KEY intentionally absent — Nginx/Caddy handles TLS
```

The Android app still uses `https://` in its server URL — the TLS handshake happens at the proxy, the traffic from proxy to Node.js is plain HTTP on localhost (trusted).

---

## Verifying WebSocket proxying

After setup, test that the WebSocket upgrade is proxied correctly:

```bash
# Install wscat
npm install -g wscat

# Connect (will fail with 401 because no token, but a 401 response
# means the WebSocket upgrade reached Node.js — proxy is working)
wscat -c "wss://astra.yourdomain.com/v1/websocket?token=invalid"
# Expected: error: Unexpected server response: 401
```

If you get a timeout or connection refused, check the Nginx/Caddy WebSocket location block.
