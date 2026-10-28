# Auto-deploy with a self-hosted GitHub Actions runner

```
git push ──► GitHub Actions ──► self-hosted runner (on the CasaOS server) ──► scripts/deploy.sh
                                                                              rsync → .env → docker compose up --build → health check
```
The runner lives on the same machine as CasaOS/Docker, so there is **no SSH and no inbound port**: the runner connects out to GitHub, picks up the job, and builds and restarts the containers locally.
All deploy logic is in [`scripts/deploy.sh`](../scripts/deploy.sh); you can also run it by hand on the server from a checkout (it keeps the existing `.env` when no secrets are passed).

`data/` (MongoDB), `exports/`, `reports/` and `.env` stay on the server and are never overwritten.
`config/seeds.json` now comes from the repo: edit it in git and push, instead of editing it on the server.

## 0. Lock down fork PRs first (public repo)
A self-hosted runner on a **public** repo can be abused by someone opening a pull request that changes the workflow.
Before registering the runner:

GitHub → repo **Settings → Actions → General → Fork pull request workflows from outside collaborators**
→ select **Require approval for all outside collaborators** → Save.

The deploy workflow itself only runs on pushes to `main` and manual runs, never on pull requests.

## 1. Prepare the server (once, in a terminal on the server: CasaOS web terminal, local console or SSH)
```bash
# Docker, curl and rsync must be present
docker compose version && curl --version | head -1 && rsync --version | head -1
# (if rsync is missing on Debian/Ubuntu)  sudo apt-get install -y rsync

# Dedicated user for the runner, allowed to use Docker
sudo useradd -m -s /bin/bash gh-runner
sudo usermod -aG docker gh-runner

# Deploy folder owned by that user
sudo mkdir -p /DATA/AppData/idea-radar
sudo chown gh-runner: /DATA/AppData/idea-radar
```
Note: membership of the `docker` group is effectively root on that machine. That's normal for a deploy runner, but it's why step 0 matters.

**Already deployed by hand?** Keep the same `MONGO_PASSWORD` you used then (MongoDB sets the password only on first start), and `sudo chown -R gh-runner: /DATA/AppData/idea-radar` except `data/`.

## 2. Register the runner
1. GitHub → repo **Settings → Actions → Runners → New self-hosted runner** → **Linux**, and the architecture of your server (`uname -m`: `x86_64` = x64, `aarch64` = ARM64).
2. On the server, as the runner user:
   ```bash
   sudo -iu gh-runner
   mkdir actions-runner && cd actions-runner
   ```
   Paste the **Download** and **Configure** commands GitHub shows. During `./config.sh`:
   - runner group: press Enter
   - name: e.g. `casaos`
   - **additional labels: `idea-radar`** (the workflow targets this label)
   - work folder: press Enter
3. Install it as a service so it survives reboots:
   ```bash
   exit                                   # back to your admin user
   cd /home/gh-runner/actions-runner
   sudo ./svc.sh install gh-runner
   sudo ./svc.sh start
   sudo ./svc.sh status
   ```
   The runner now shows **Idle** under Settings → Actions → Runners.

## 3. Add secrets
GitHub → repo **Settings → Secrets and variables → Actions**:

| Name | Kind | Required |
|---|---|---|
| `MONGO_PASSWORD` | Secret | yes — long random string |
| `REDDIT_CLIENT_ID`, `REDDIT_CLIENT_SECRET` | Secret | after Reddit approves API access |
| `REDDIT_USERNAME` | Variable | with the Reddit secrets |
| `BRAVE_API_KEY` | Secret | optional |
| `DEPLOY_DIR` | Variable | only if not `/DATA/AppData/idea-radar` |

Or from your laptop: `gh secret set MONGO_PASSWORD --repo nsiva7/idea-radar` (it prompts for the value).

## 4. First deploy
GitHub → **Actions → Deploy → Run workflow**, or push any change to `main`.
The run ends with the collector's `/status` JSON. The first collection starts right after the containers come up and takes 1–2 hours.

## Troubleshooting
- **Job stays "Queued"**: runner offline or missing the `idea-radar` label (`sudo ./svc.sh status`).
- **permission denied on docker.sock**: `gh-runner` isn't in the `docker` group yet → `sudo ./svc.sh stop && sudo ./svc.sh start` after `usermod`.
- **mongo container restarting**: old CPU without AVX → change `image: mongo:7` to `mongo:4.4` in `docker-compose.yml`, push.
- **Health check fails**: the job prints the last 80 log lines; also `docker compose logs collector` on the server.
