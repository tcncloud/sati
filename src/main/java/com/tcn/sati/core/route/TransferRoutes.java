package com.tcn.sati.core.route;

import com.tcn.sati.core.service.TransferService;
import com.tcn.sati.core.service.dto.TransferDto;
import com.tcn.sati.core.service.dto.SuccessResult;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.*;

import java.util.Map;

/**
 * Routes for call transfer operations — thin HTTP layer, delegates to
 * TransferService.
 */
public class TransferRoutes {
        private static TransferService service;

        public static void register(Javalin app, TransferService svc) {
                service = svc;

                app.post("/api/transfer", TransferRoutes::transfer);
                app.put("/api/transfer/{partnerAgentId}/hold-caller", TransferRoutes::holdCaller);
                app.put("/api/transfer/{partnerAgentId}/unhold-caller", TransferRoutes::unholdCaller);
                app.put("/api/transfer/{partnerAgentId}/hold-agent", TransferRoutes::holdAgent);
                app.put("/api/transfer/{partnerAgentId}/unhold-agent", TransferRoutes::unholdAgent);
        }

        @OpenApi(path = "/api/transfer", methods = HttpMethod.POST, summary = "Transfer a Call", tags = {
                        "Transfer" }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = TransferDto.TransferRequest.class)), responses = {
                                        @OpenApiResponse(status = "200", content = @OpenApiContent(from = TransferDto.TransferResponse.class)),
                                        @OpenApiResponse(status = "400", description = "Missing required fields")
                        })
        private static void transfer(Context ctx) {
                var body = ctx.bodyAsClass(TransferDto.TransferRequest.class);

                if (body.partnerAgentId == null || body.partnerAgentId.isBlank()) {
                        ctx.status(400).json(Map.of("error", "partnerAgentId is required"));
                        return;
                }

                if (body.receivingPartnerAgentId == null
                                && body.outboundDestination == null
                                && !body.queue) {
                        ctx.status(400).json(Map.of("error",
                                        "One destination required: receivingPartnerAgentId, outbound, or queue"));
                        return;
                }

                ctx.json(service.executeTransfer(body));
        }

        @OpenApi(path = "/api/transfer/{partnerAgentId}/hold-caller", methods = HttpMethod.PUT, summary = "Hold Caller During Transfer", tags = {
                        "Transfer" }, pathParams = @OpenApiParam(name = "partnerAgentId", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
        private static void holdCaller(Context ctx) {
                ctx.json(service.holdCaller(ctx.pathParam("partnerAgentId")));
        }

        @OpenApi(path = "/api/transfer/{partnerAgentId}/unhold-caller", methods = HttpMethod.PUT, summary = "Unhold Caller During Transfer", tags = {
                        "Transfer" }, pathParams = @OpenApiParam(name = "partnerAgentId", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
        private static void unholdCaller(Context ctx) {
                ctx.json(service.unholdCaller(ctx.pathParam("partnerAgentId")));
        }

        @OpenApi(path = "/api/transfer/{partnerAgentId}/hold-agent", methods = HttpMethod.PUT, summary = "Hold Agent During Transfer", tags = {
                        "Transfer" }, pathParams = @OpenApiParam(name = "partnerAgentId", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
        private static void holdAgent(Context ctx) {
                ctx.json(service.holdAgent(ctx.pathParam("partnerAgentId")));
        }

        @OpenApi(path = "/api/transfer/{partnerAgentId}/unhold-agent", methods = HttpMethod.PUT, summary = "Unhold Agent During Transfer", tags = {
                        "Transfer" }, pathParams = @OpenApiParam(name = "partnerAgentId", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
        private static void unholdAgent(Context ctx) {
                ctx.json(service.unholdAgent(ctx.pathParam("partnerAgentId")));
        }
}
