import assert from "node:assert/strict";
import test from "node:test";

import worker from "../src/index.js";

const env = {
  EMQX_API_URL: "https://broker.example.com:8443/api/v5",
  EMQX_APP_ID: "test-app",
  EMQX_APP_SECRET: "test-secret",
  APP_API_TOKEN: "test-token",
  DEVICE_ID: "7CE8B1B1FC9C",
  DEVICE_COMMAND_TOPIC: "devices/7CE8B1B1FC9C/commands/display"
};

function messageRequest(body, token = "test-token", deviceId = env.DEVICE_ID) {
  return new Request(`https://worker.example.com/api/v1/devices/${deviceId}/messages`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${token}`,
      "content-type": "application/json"
    },
    body: JSON.stringify(body)
  });
}

test("health endpoint does not expose secrets", async () => {
  const response = await worker.fetch(new Request("https://worker.example.com/health"), env);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    success: true,
    service: "esp32-message-api",
    deviceId: env.DEVICE_ID
  });
});

test("message endpoint rejects an invalid token", async () => {
  const response = await worker.fetch(messageRequest({ text: "hello" }, "wrong-token"), env);
  assert.equal(response.status, 401);
  assert.equal((await response.json()).error.code, "UNAUTHORIZED");
});

test("message endpoint validates text and durations", async () => {
  const emptyText = await worker.fetch(messageRequest({ text: "   " }), env);
  assert.equal(emptyText.status, 422);

  const invalidDuration = await worker.fetch(
    messageRequest({ text: "hello", buzzerDurationMs: 10001 }),
    env
  );
  assert.equal(invalidDuration.status, 422);
});

test("message endpoint publishes a QoS 1 non-retained command", async () => {
  const originalFetch = globalThis.fetch;
  let capturedRequest;
  globalThis.fetch = async (url, init) => {
    capturedRequest = { url, init };
    return new Response(JSON.stringify({ id: "broker-message-id" }), {
      status: 200,
      headers: { "content-type": "application/json" }
    });
  };

  try {
    const response = await worker.fetch(messageRequest({ text: "  Hello ESP32  " }), env);
    assert.equal(response.status, 202);
    const responseBody = await response.json();
    assert.equal(responseBody.success, true);
    assert.equal(responseBody.accepted, true);

    assert.equal(capturedRequest.url, `${env.EMQX_API_URL}/publish`);
    assert.match(capturedRequest.init.headers.authorization, /^Basic /);
    const publishBody = JSON.parse(capturedRequest.init.body);
    assert.equal(publishBody.topic, env.DEVICE_COMMAND_TOPIC);
    assert.equal(publishBody.qos, 1);
    assert.equal(publishBody.retain, false);

    const command = JSON.parse(publishBody.payload);
    assert.equal(command.text, "Hello ESP32");
    assert.equal(command.buzzerDurationMs, 3000);
    assert.equal(command.displayDurationMs, 10000);
    assert.equal(command.deviceId, env.DEVICE_ID);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("message endpoint maps EMQX failures to 502", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response("unauthorized", { status: 401 });
  try {
    const response = await worker.fetch(messageRequest({ text: "hello" }), env);
    assert.equal(response.status, 502);
    assert.equal((await response.json()).error.code, "MQTT_PUBLISH_FAILED");
  } finally {
    globalThis.fetch = originalFetch;
  }
});
