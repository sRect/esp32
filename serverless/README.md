# ESP32 Message API

Cloudflare Worker that accepts authenticated Android requests and publishes display commands to EMQX Cloud.

## Endpoints

### Health check

```http
GET /health
```

### Send a device message

```http
POST /api/v1/devices/7CE8B1B1FC9C/messages
Authorization: Bearer <APP_API_TOKEN>
Content-Type: application/json
```

```json
{
  "text": "Hello ESP32",
  "displayDurationMs": 10000,
  "buzzerDurationMs": 3000
}
```

`displayDurationMs` is optional and accepts 1,000–60,000 ms. `buzzerDurationMs` is optional and accepts 0–10,000 ms. The default buzzer duration is 3 seconds.

The Worker publishes a QoS 1, non-retained JSON command to the topic configured by `DEVICE_COMMAND_TOPIC`.

## Runtime configuration

Configure these in Cloudflare under **Settings → Variables and Secrets**:

| Name | Type |
|---|---|
| `EMQX_API_URL` | Text |
| `DEVICE_ID` | Text |
| `DEVICE_COMMAND_TOPIC` | Text |
| `EMQX_APP_ID` | Secret |
| `EMQX_APP_SECRET` | Secret |
| `APP_API_TOKEN` | Secret |

Never commit real secret values. Copy `.dev.vars.example` to `.dev.vars` only for local development; `.dev.vars` is ignored by Git.

## Commands

```bash
npm install
npm test
npm run dev
npm run deploy
```

`keep_vars` is enabled in `wrangler.jsonc`, so deploying source code does not remove variables and secrets already configured in the Cloudflare dashboard.
