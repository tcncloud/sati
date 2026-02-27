package com.tcn.sati.core.service;

import com.tcn.sati.core.service.dto.SuccessResult;
import com.tcn.sati.core.service.dto.TransferDto.TransferRequest;
import com.tcn.sati.core.service.dto.TransferDto.TransferResponse;
import com.tcn.sati.infra.gate.GateClient;

/**
 * Transfer service — contains the business logic for transfer operations.
 * Routes delegate here. Subclass to override behavior (e.g.,
 * CustomAppTransferService).
 *
 * Default implementation calls GateClient gRPC directly.
 */
public class TransferService {
    protected final GateClient gate;

    public TransferService(GateClient gate) {
        this.gate = gate;
    }

    // ========== Business Logic (override these) ==========

    public TransferResponse executeTransfer(TransferRequest req) {
        var kind = req.kind != null ? req.kind.toUpperCase() : "COLD";
        var action = req.action != null ? req.action.toUpperCase() : "START";

        var protoBuilder = build.buf.gen.tcnapi.exile.gate.v2.TransferRequest.newBuilder()
                .setPartnerAgentId(req.partnerAgentId)
                .setKind(build.buf.gen.tcnapi.exile.gate.v2.TransferRequest.Kind.valueOf("KIND_" + kind))
                .setAction(build.buf.gen.tcnapi.exile.gate.v2.TransferRequest.Action.valueOf("ACTION_" + action));

        if (req.receivingPartnerAgentId != null) {
            protoBuilder.setReceivingPartnerAgentId(
                    build.buf.gen.tcnapi.exile.gate.v2.TransferRequest.Agent.newBuilder()
                            .setPartnerAgentId(req.receivingPartnerAgentId).build());
        } else if (req.outboundDestination != null) {
            var ob = build.buf.gen.tcnapi.exile.gate.v2.TransferRequest.Outbound.newBuilder()
                    .setDestination(req.outboundDestination);
            if (req.outboundCallerId != null)
                ob.setCallerId(req.outboundCallerId);
            if (req.outboundCallerHold != null)
                ob.setCallerHold(req.outboundCallerHold);
            protoBuilder.setOutbound(ob.build());
        } else if (req.queue) {
            protoBuilder.setQueue(build.buf.gen.tcnapi.exile.gate.v2.TransferRequest.Queue.newBuilder().build());
        }

        gate.transfer(protoBuilder.build());
        return new TransferResponse(true, "Transfer initiated");
    }

    public SuccessResult holdCaller(String agentId) {
        gate.holdTransferMemberCaller(
                build.buf.gen.tcnapi.exile.gate.v2.HoldTransferMemberCallerRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return new SuccessResult();
    }

    public SuccessResult unholdCaller(String agentId) {
        gate.unholdTransferMemberCaller(
                build.buf.gen.tcnapi.exile.gate.v2.UnholdTransferMemberCallerRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return new SuccessResult();
    }

    public SuccessResult holdAgent(String agentId) {
        gate.holdTransferMemberAgent(
                build.buf.gen.tcnapi.exile.gate.v2.HoldTransferMemberAgentRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return new SuccessResult();
    }

    public SuccessResult unholdAgent(String agentId) {
        gate.unholdTransferMemberAgent(
                build.buf.gen.tcnapi.exile.gate.v2.UnholdTransferMemberAgentRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return new SuccessResult();
    }
}
