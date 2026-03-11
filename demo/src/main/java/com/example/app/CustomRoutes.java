package com.example.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;

import java.time.Instant;
import java.util.Map;

// Example of adding completely custom Swagger endpoints from a client app.
//
// This class lives entirely in the demo (client) project — no changes to sati.
// After SatiApp.start(), call:
//   CustomRoutes.register(satiApp.getApp());
//
// How it works:
//   1. Register your HTTP handlers normally (app.get/post/etc)
//   2. Add an "after" filter on /swagger-docs* that injects your endpoint
//      definitions into the OpenAPI JSON that sati already generated.
//   This way sati's endpoints stay intact and yours get added alongside them.
public class CustomRoutes {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void register(Javalin app) {
        // 1. Register HTTP handlers
        app.get("/api/custom/health", ctx -> {
            ctx.json(Map.of(
                    "status", "ok",
                    "timestamp", Instant.now().toString()
            ));
        });

        app.post("/api/custom/echo", ctx -> {
            var body = ctx.bodyAsClass(Map.class);
            ctx.json(Map.of(
                    "echo", body.getOrDefault("message", ""),
                    "receivedAt", System.currentTimeMillis()
            ));
        });

        // 2. Inject custom endpoint docs into swagger spec at runtime
        app.after("/swagger-docs*", ctx -> {
            String contentType = ctx.res().getContentType();
            if (contentType == null || !contentType.contains("application/json")) return;

            String body = ctx.result();
            if (body == null || body.isEmpty()) return;

            try {
                var root = (ObjectNode) mapper.readTree(body);
                var paths = (ObjectNode) root.get("paths");
                if (paths == null) return;

                var schemas = root.withObject("/components").withObject("/schemas");

                // --- GET /api/custom/health ---
                paths.set("/api/custom/health", mapper.readTree("""
                        {
                          "get": {
                            "tags": ["Custom"],
                            "summary": "Custom Health Check",
                            "description": "A custom health endpoint defined entirely in the client app.",
                            "responses": {
                              "200": {
                                "description": "OK",
                                "content": {
                                  "application/json": {
                                    "schema": { "$ref": "#/components/schemas/HealthResponse" }
                                  }
                                }
                              }
                            }
                          }
                        }
                        """));

                schemas.set("HealthResponse", mapper.readTree("""
                        {
                          "type": "object",
                          "properties": {
                            "status":    { "type": "string" },
                            "timestamp": { "type": "string" }
                          }
                        }
                        """));

                // --- POST /api/custom/echo ---
                paths.set("/api/custom/echo", mapper.readTree("""
                        {
                          "post": {
                            "tags": ["Custom"],
                            "summary": "Echo Message",
                            "description": "Echoes back whatever you send.",
                            "requestBody": {
                              "content": {
                                "application/json": {
                                  "schema": { "$ref": "#/components/schemas/EchoRequest" }
                                }
                              }
                            },
                            "responses": {
                              "200": {
                                "description": "OK",
                                "content": {
                                  "application/json": {
                                    "schema": { "$ref": "#/components/schemas/EchoResponse" }
                                  }
                                }
                              }
                            }
                          }
                        }
                        """));

                schemas.set("EchoRequest", mapper.readTree("""
                        {
                          "type": "object",
                          "properties": {
                            "message": { "type": "string" }
                          }
                        }
                        """));

                schemas.set("EchoResponse", mapper.readTree("""
                        {
                          "type": "object",
                          "properties": {
                            "echo":       { "type": "string" },
                            "receivedAt": { "type": "integer", "format": "int64" }
                          }
                        }
                        """));

                ctx.result(mapper.writeValueAsString(root));
            } catch (Exception e) {
                // If injection fails, just serve the original spec — sati endpoints still work
            }
        });
    }
}
