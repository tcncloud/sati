package com.tcn.sati.core.service;

import com.tcn.sati.infra.gate.GateClient;

import java.util.Map;

/**
 * Transfer service — contains the business logic for transfer operations.
 * Routes delegate here. Subclass to override behavior (e.g.,
 * FinviTransferService).
 *
 * Default implementation calls GateClient gRPC directly.
 */
public class TransferService {
    protected final GateClient gate;

    public TransferService(GateClient gate) {
        this.gate = gate;
    }

    // ========== Request/Response types (extendable) ==========

    public static class TransferRequest {
        private String partnerAgentId;
        private String kind; // COLD or WARM
        private String action; // START, CANCEL, COMPLETE
        private String receivingPartnerAgentId;
        private String outboundDestination;
        private String outboundCallerId;
        private Boolean outboundCallerHold;
        private boolean queue;

        public String getPartnerAgentId() {
            return partnerAgentId;
        }

        public void setPartnerAgentId(String v) {
            partnerAgentId = v;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String v) {
            kind = v;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String v) {
            action = v;
        }

        public String getReceivingPartnerAgentId() {
            return receivingPartnerAgentId;
        }

        public void setReceivingPartnerAgentId(String v) {
            receivingPartnerAgentId = v;
        }

        public String getOutboundDestination() {
            return outboundDestination;
        }

        public void setOutboundDestination(String v) {
            outboundDestination = v;
        }

        public String getOutboundCallerId() {
            return outboundCallerId;
        }

        public void setOutboundCallerId(String v) {
            outboundCallerId = v;
        }

        public Boolean getOutboundCallerHold() {
            return outboundCallerHold;
        }

        public void setOutboundCallerHold(Boolean v) {
            outboundCallerHold = v;
        }

        public boolean isQueue() {
            return queue;
        }

        public void setQueue(boolean v) {
            queue = v;
        }
    }

    public static class TransferResponse {
        private boolean success;
        private String message;

        public TransferResponse() {
        }

        public TransferResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean v) {
            success = v;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String v) {
            message = v;
        }
    }

    // ========== Business Logic (override these) ==========

    public TransferResponse executeTransfer(TransferRequest req) {
        var kind = req.getKind() != null ? req.getKind().toUpperCase() : "COLD";
        var action = req.getAction() != null ? req.getAction().toUpperCase() : "START";

        var protoBuilder = build.buf.gen.tcnapi.exile.gate.v2.TransferRequest.newBuilder()
                .setPartnerAgentId(req.getPartnerAgentId())
                .setKind(build.buf.gen.tcnapi.exile.gate.v2.TransferRequest.Kind.valueOf("KIND_" + kind))
                .setAction(build.buf.gen.tcnapi.exile.gate.v2.TransferRequest.Action.valueOf("ACTION_" + action));

        if (req.getReceivingPartnerAgentId() != null) {
            protoBuilder.setReceivingPartnerAgentId(
                    build.buf.gen.tcnapi.exile.gate.v2.TransferRequest.Agent.newBuilder()
                            .setPartnerAgentId(req.getReceivingPartnerAgentId()).build());
        } else if (req.getOutboundDestination() != null) {
            var ob = build.buf.gen.tcnapi.exile.gate.v2.TransferRequest.Outbound.newBuilder()
                    .setDestination(req.getOutboundDestination());
            if (req.getOutboundCallerId() != null)
                ob.setCallerId(req.getOutboundCallerId());
            if (req.getOutboundCallerHold() != null)
                ob.setCallerHold(req.getOutboundCallerHold());
            protoBuilder.setOutbound(ob.build());
        } else if (req.isQueue()) {
            protoBuilder.setQueue(build.buf.gen.tcnapi.exile.gate.v2.TransferRequest.Queue.newBuilder().build());
        }

        gate.transfer(protoBuilder.build());
        return new TransferResponse(true, "Transfer initiated");
    }

    public Map<String, Object> holdCaller(String agentId) {
        gate.holdTransferMemberCaller(
                build.buf.gen.tcnapi.exile.gate.v2.HoldTransferMemberCallerRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return Map.of("success", true);
    }

    public Map<String, Object> unholdCaller(String agentId) {
        gate.unholdTransferMemberCaller(
                build.buf.gen.tcnapi.exile.gate.v2.UnholdTransferMemberCallerRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return Map.of("success", true);
    }

    public Map<String, Object> holdAgent(String agentId) {
        gate.holdTransferMemberAgent(
                build.buf.gen.tcnapi.exile.gate.v2.HoldTransferMemberAgentRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return Map.of("success", true);
    }

    public Map<String, Object> unholdAgent(String agentId) {
        gate.unholdTransferMemberAgent(
                build.buf.gen.tcnapi.exile.gate.v2.UnholdTransferMemberAgentRequest.newBuilder()
                        .setPartnerAgentId(agentId).build());
        return Map.of("success", true);
    }
}
