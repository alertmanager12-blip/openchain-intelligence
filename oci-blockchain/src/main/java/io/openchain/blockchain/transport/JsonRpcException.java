package io.openchain.blockchain.transport;

import io.openchain.core.provider.ProviderException;
import java.util.Locale;

/** A JSON-RPC {@code error} object returned by the node (HTTP succeeded). Carries the error code for classification. */
public final class JsonRpcException extends ProviderException {
    /** JSON-RPC 2.0: the method does not exist / is not available. */
    public static final int METHOD_NOT_FOUND = -32601;
    /** EIP-1474: method not supported. */
    public static final int METHOD_NOT_SUPPORTED = -32004;

    private final String method;
    private final int code;
    private final String rpcMessage;

    public JsonRpcException(String message, String method, int code, String rpcMessage, boolean retryable) {
        super(message, retryable);
        this.method = method;
        this.code = code;
        this.rpcMessage = rpcMessage;
    }

    public String method() { return method; }
    public int code() { return code; }
    public String rpcMessage() { return rpcMessage; }

    /**
     * True when an {@code eth_getLogs} answer means "ask for a smaller block range": too many results or a range above
     * the provider's limit. Observed wordings (any code): "query returned more than 10000 results" (Infura -32005),
     * "Log response size exceeded ... block range" (Alchemy -32602), "exceed maximum block range: 5000" (geth-based
     * public endpoints), "block range is too wide" / "block range too large". Rate limiting ("rate limit", "request
     * count") is NOT a range error.
     */
    public boolean rangeTooLarge() {
        if (rpcMessage == null) return false;
        String m = rpcMessage.toLowerCase(Locale.ROOT);
        if (m.contains("rate limit") || m.contains("rate-limit") || m.contains("request count") || m.contains("too many requests")) return false;
        return m.contains("more than") && m.contains("result") || m.contains("too many results") || m.contains("response size")
                || m.contains("block range") || m.contains("blocks range") || m.contains("range is too") || m.contains("range too")
                || m.contains("maximum range") || m.contains("max range") || m.contains("ranges over");
    }

    /**
     * True when the node says it does not implement the method (as opposed to bad params, limits or transient
     * failures): standard codes -32601 / -32004, or the generic server error -32000 with an explicit
     * "not found / not supported / does not exist / not available" message (Geth-style and several gateways).
     */
    public boolean methodUnsupported() {
        if (code == METHOD_NOT_FOUND || code == METHOD_NOT_SUPPORTED) return true;
        if (code != -32000 || rpcMessage == null) return false;
        String m = rpcMessage.toLowerCase(Locale.ROOT);
        return m.contains("method") && (m.contains("not found") || m.contains("not supported") || m.contains("unsupported")
                || m.contains("does not exist") || m.contains("not available"));
    }
}
