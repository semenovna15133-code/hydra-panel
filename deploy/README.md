# Hydra Panel Deployment

Production deployment scripts for Hydra Control Panel on Ubuntu 24.04.

## Architecture

- Single-process mode: uvicorn without --workers
- TLS termination: Panel itself terminates TLS (no reverse proxy)
- Port 443: Requires CAP_NET_BIND_SERVICE via systemd AmbientCapabilities
- Secrets: EnvironmentFile=/etc/hydra/panel.env (not visible via systemctl show)

## Prerequisites

- Ubuntu 24.04 LTS VPS
- Root SSH access
- Public domain pointing to VPS IP
- Port 80 open (for certbot HTTP-01 challenge)
- Port 443 open (for panel HTTPS)

## Quick Start

1. Clone repository:
   git clone https://github.com/semenovna15133-code/hydra-panel.git /opt/hydra/repo
   cd /opt/hydra/repo

2. Run deployment script:
   sudo bash deploy/deploy.sh --domain panel.hydra.example

3. Verify:
   curl https://panel.hydra.example/health

## Files

- deploy.sh - main deployment script
- systemd/hydra-panel.service - systemd unit file
- hooks/pre-renew - certbot pre-hook (open port 80)
- hooks/post-renew - certbot post-hook (close port 80)
- hooks/deploy - certbot deploy-hook (chown + restart)

## Security Notes

- No reverse proxy: Panel reads client IP from request.remote_addr
- EnvironmentFile: Secrets in /etc/hydra/panel.env (mode 600, owned by hydra)
- CAP_NET_BIND_SERVICE: Scoped to systemd unit, not global Python binary
- Single-process: Simplifies state but limits concurrency
