package cn.tohsaka.factory.zstdnet.vcbgpublic.bridge;

record VoiceChatPassthroughDecision(UdpRoute route, boolean reuseGameRoute, String reason) {
    static VoiceChatPassthroughDecision disabled(String reason) {
        return new VoiceChatPassthroughDecision(null, false, reason);
    }

    static VoiceChatPassthroughDecision reuseGameRoute(String reason) {
        return new VoiceChatPassthroughDecision(null, true, reason);
    }

    static VoiceChatPassthroughDecision route(UdpRoute route) {
        return new VoiceChatPassthroughDecision(route, false, null);
    }
}
