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

        var protoBuilder = build.buf.gen.tcnapi.exile.gate.v3.TransferRequest.newBuilder()
                .setPartnerAgentId(req.partner_agent_id)
                .setKind(build.buf.gen.tcnapi.exile.gate.v3.TransferRequest.TransferKind.valueOf("TRANSFER_KIND_" + kind))
                .setAction(build.buf.gen.tcnapi.exile.gate.v3.TransferRequest.TransferAction.valueOf("TRANSFER_ACTION_" + action));

        if (req.receiving_partner_agent_id != null) {
            protoBuilder.setAgent(
                    build.buf.gen.tcnapi.exile.gate.v3.TransferRequest.AgentDestination.newBuilder()
                            .setPartnerAgentId(req.receiving_partner_agent_id.partner_agent_id).build());
        } else if (req.outbound != null) {
            protoBuilder.setOutbound(
                    build.buf.gen.tcnapi.exile.gate.v3.TransferRequest.OutboundDestination.newBuilder()
                            .setPhoneNumber(req.outbound.destination).build());
        } else if (req.queue != null) {
            protoBuilder.setQueue(
                    build.buf.gen.tcnapi.exile.gate.v3.TransferRequest.QueueDestination.newBuilder().build());
        }

        gate.transfer(protoBuilder.build());
        return new TransferResponse();
    }

    public SuccessResult holdCaller(String agentId) {
        gate.setHoldState(
                build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.newBuilder()
                        .setPartnerAgentId(agentId)
                        .setTarget(build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.HoldTarget.HOLD_TARGET_TRANSFER_CALLER)
                        .setAction(build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.HoldAction.HOLD_ACTION_HOLD)
                        .build());
        return new SuccessResult();
    }

    public SuccessResult unholdCaller(String agentId) {
        gate.setHoldState(
                build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.newBuilder()
                        .setPartnerAgentId(agentId)
                        .setTarget(build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.HoldTarget.HOLD_TARGET_TRANSFER_CALLER)
                        .setAction(build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.HoldAction.HOLD_ACTION_UNHOLD)
                        .build());
        return new SuccessResult();
    }

    public SuccessResult holdAgent(String agentId) {
        gate.setHoldState(
                build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.newBuilder()
                        .setPartnerAgentId(agentId)
                        .setTarget(build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.HoldTarget.HOLD_TARGET_TRANSFER_AGENT)
                        .setAction(build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.HoldAction.HOLD_ACTION_HOLD)
                        .build());
        return new SuccessResult();
    }

    public SuccessResult unholdAgent(String agentId) {
        gate.setHoldState(
                build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.newBuilder()
                        .setPartnerAgentId(agentId)
                        .setTarget(build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.HoldTarget.HOLD_TARGET_TRANSFER_AGENT)
                        .setAction(build.buf.gen.tcnapi.exile.gate.v3.SetHoldStateRequest.HoldAction.HOLD_ACTION_UNHOLD)
                        .build());
        return new SuccessResult();
    }
}
