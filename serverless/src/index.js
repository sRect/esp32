const MAX_REQUEST_BYTES = 4096;
const MAX_TEXT_LENGTH = 120;
const DEFAULT_DISPLAY_DURATION_MS = 10000;
const DEFAULT_BUZZER_DURATION_MS = 3000;
const MAX_DISPLAY_DURATION_MS = 60000;
const MAX_BUZZER_DURATION_MS = 10000;

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, POST, OPTIONS",
  "access-control-allow-headers": "Authorization, Content-Type",
  "access-control-max-age": "86400"
};

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

function errorResponse(status, code, message) {
  return jsonResponse({ success: false, error: { code, message } }, status);
}

function hasRequiredConfiguration(env) {
  return [
    "EMQX_API_URL",
    "EMQX_APP_ID",
    "EMQX_APP_SECRET",
    "APP_API_TOKEN",
    "DEVICE_ID",
    "DEVICE_COMMAND_TOPIC"
  ].every((key) => typeof env[key] === "string" && env[key].length > 0);
}

function timingSafeStringEqual(left, right) {
  const encoder = new TextEncoder();
  const leftBytes = encoder.encode(left);
  const rightBytes = encoder.encode(right);
  const length = Math.max(leftBytes.length, rightBytes.length);
  let difference = leftBytes.length ^ rightBytes.length;
  for (let index = 0; index < length; index += 1) {
    difference |= (leftBytes[index] ?? 0) ^ (rightBytes[index] ?? 0);
  }
  return difference === 0;
}

function isAuthorized(request, env) {
  const authorization = request.headers.get("authorization") ?? "";
  const prefix = "Bearer ";
  if (!authorization.startsWith(prefix)) {
    return false;
  }
  return timingSafeStringEqual(authorization.slice(prefix.length), env.APP_API_TOKEN);
}

function normalizeInteger(value, fallback, minimum, maximum) {
  if (value === undefined) {
    return fallback;
  }
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    return null;
  }
  return value;
}

function validateMessage(body) {
  if (body === null || typeof body !== "object" || Array.isArray(body)) {
    return { error: "Request body must be a JSON object" };
  }

  if (typeof body.text !== "string") {
    return { error: "text must be a string" };
  }
  const text = body.text.trim();
  const textLength = [...text].length;
  if (textLength < 1 || textLength > MAX_TEXT_LENGTH) {
    return { error: `text must contain between 1 and ${MAX_TEXT_LENGTH} characters` };
  }

  const displayDurationMs = normalizeInteger(
    body.displayDurationMs,
    DEFAULT_DISPLAY_DURATION_MS,
    1000,
    MAX_DISPLAY_DURATION_MS
  );
  if (displayDurationMs === null) {
    return { error: `displayDurationMs must be an integer between 1000 and ${MAX_DISPLAY_DURATION_MS}` };
  }

  const buzzerDurationMs = normalizeInteger(
    body.buzzerDurationMs,
    DEFAULT_BUZZER_DURATION_MS,
    0,
    MAX_BUZZER_DURATION_MS
  );
  if (buzzerDurationMs === null) {
    return { error: `buzzerDurationMs must be an integer between 0 and ${MAX_BUZZER_DURATION_MS}` };
  }

  return { value: { text, displayDurationMs, buzzerDurationMs } };
}

function basicAuthorization(username, password) {
  const bytes = new TextEncoder().encode(`${username}:${password}`);
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return `Basic ${btoa(binary)}`;
}

async function publishDeviceMessage(env, message) {
  const apiUrl = `${env.EMQX_API_URL.replace(/\/+$/, "")}/publish`;
  const response = await fetch(apiUrl, {
    method: "POST",
    headers: {
      authorization: basicAuthorization(env.EMQX_APP_ID, env.EMQX_APP_SECRET),
      "content-type": "application/json"
    },
    body: JSON.stringify({
      topic: env.DEVICE_COMMAND_TOPIC,
      qos: 1,
      retain: false,
      payload_encoding: "plain",
      payload: JSON.stringify(message)
    })
  });

  const responseText = await response.text();
  let responseBody = null;
  if (responseText) {
    try {
      responseBody = JSON.parse(responseText);
    } catch {
      responseBody = { message: responseText.slice(0, 256) };
    }
  }

  if (!response.ok) {
    console.error("EMQX publish failed", response.status, responseBody);
    throw new Error(`EMQX publish failed with HTTP ${response.status}`);
  }
  return responseBody;
}

async function handleMessageRequest(request, env, deviceId) {
  if (!isAuthorized(request, env)) {
    return errorResponse(401, "UNAUTHORIZED", "Missing or invalid bearer token");
  }
  if (deviceId !== env.DEVICE_ID) {
    return errorResponse(404, "DEVICE_NOT_FOUND", "Device was not found");
  }

  const contentType = request.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().includes("application/json")) {
    return errorResponse(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json");
  }

  const contentLength = Number(request.headers.get("content-length") ?? 0);
  if (contentLength > MAX_REQUEST_BYTES) {
    return errorResponse(413, "REQUEST_TOO_LARGE", "Request body is too large");
  }

  const rawBody = await request.text();
  if (new TextEncoder().encode(rawBody).length > MAX_REQUEST_BYTES) {
    return errorResponse(413, "REQUEST_TOO_LARGE", "Request body is too large");
  }

  let body;
  try {
    body = JSON.parse(rawBody);
  } catch {
    return errorResponse(400, "INVALID_JSON", "Request body must be valid JSON");
  }

  const validation = validateMessage(body);
  if (validation.error) {
    return errorResponse(422, "INVALID_MESSAGE", validation.error);
  }

  const message = {
    messageId: crypto.randomUUID(),
    deviceId: env.DEVICE_ID,
    type: "display",
    text: validation.value.text,
    displayDurationMs: validation.value.displayDurationMs,
    buzzerDurationMs: validation.value.buzzerDurationMs,
    sentAt: Date.now()
  };

  try {
    const brokerResponse = await publishDeviceMessage(env, message);
    return jsonResponse({
      success: true,
      messageId: message.messageId,
      deviceId: message.deviceId,
      accepted: true,
      brokerResponse
    }, 202);
  } catch (error) {
    console.error("Unable to publish device message", error);
    return errorResponse(502, "MQTT_PUBLISH_FAILED", "Unable to publish the device message");
  }
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: JSON_HEADERS });
    }

    if (!hasRequiredConfiguration(env)) {
      console.error("Worker configuration is incomplete");
      return errorResponse(503, "SERVICE_NOT_CONFIGURED", "Service configuration is incomplete");
    }

    const url = new URL(request.url);
    if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/health")) {
      return jsonResponse({
        success: true,
        service: "esp32-message-api",
        deviceId: env.DEVICE_ID
      });
    }

    const messageRoute = url.pathname.match(/^\/api\/v1\/devices\/([^/]+)\/messages\/?$/);
    if (request.method === "POST" && messageRoute) {
      return handleMessageRequest(request, env, decodeURIComponent(messageRoute[1]));
    }

    return errorResponse(404, "NOT_FOUND", "API endpoint not found");
  }
};
