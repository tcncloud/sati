package com.tcn.sati.core.route;

import com.tcn.sati.core.service.TransferService;
import com.tcn.sati.core.service.TransferService.TransferRequest;
import com.tcn.sati.core.service.TransferService.TransferResponse;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.*;

/**
 * Routes for transfer operations.
 */
public class TransferRoutes {

    private static TransferService transferService;

    public static void register(Javalin app, TransferService service) {
        transferService = service;
        app.post("/api/transfer", TransferRoutes::handleTransfer);
    }

    @OpenApi(path = "/api/transfer", methods = HttpMethod.POST, summary = "Transfer a call", tags = {
            "Transfer" }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = TransferRequest.class)), responses = {
                    @OpenApiResponse(status = "200", content = @OpenApiContent(from = TransferResponse.class))
            })
    private static void handleTransfer(Context ctx) {
        TransferRequest req = ctx.bodyAsClass(TransferRequest.class);
        TransferResponse response = transferService.executeTransfer(req);
        ctx.json(response);
    }
}
