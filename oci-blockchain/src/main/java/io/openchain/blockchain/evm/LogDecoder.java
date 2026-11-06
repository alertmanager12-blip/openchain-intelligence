package io.openchain.blockchain.evm;

import static io.openchain.blockchain.evm.EvmCodec.*;

import io.openchain.blockchain.token.StablecoinFreezeSources;
import io.openchain.blockchain.token.TokenRegistry;
import io.openchain.core.model.Address;
import io.openchain.core.model.ContractAdminChange;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenApproval;
import io.openchain.core.model.TokenFreeze;
import io.openchain.core.model.TokenTransfer;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Decodes the EVM/TVM logs OCI understands (TVM logs have the EVM layout): ERC-20/TRC-20 {@code Transfer} and
 * {@code Approval} (3 topics + one 32-byte data word; the ERC-721 variants have 4 topics and are ignored) and issuer
 * blacklist events of {@link StablecoinFreezeSources} contracts (address as topic 1, or as the single data word for
 * Tether's non-indexed {@code AddedBlackList}), and contract admin changes of ANY contract ({@code OwnershipTransferred}
 * with indexed or non-indexed parameters, EIP-1967 {@code Upgraded} / {@code AdminChanged}; scope filtering is the
 * indexer's job). Anything else is ignored.
 */
public final class LogDecoder {

    /** Mutable result of decoding the logs of one block. */
    public static final class Decoded {
        public final List<TokenTransfer> transfers = new ArrayList<>();
        public final List<TokenApproval> approvals = new ArrayList<>();
        public final List<TokenFreeze> freezes = new ArrayList<>();
        public final List<ContractAdminChange> adminChanges = new ArrayList<>();
    }

    private final Network network;
    private final TokenRegistry tokens;
    private final StablecoinFreezeSources freezes;
    private final Function<String, Address> addressOfHex40;
    private final Function<String, String> contractOf;

    /**
     * @param addressOfHex40 20-byte hex (no prefix, lowercase) -> network address
     * @param contractOf     raw {@code log.address} -> contract id in the registry's form
     */
    public LogDecoder(Network network, TokenRegistry tokens, StablecoinFreezeSources freezes,
                      Function<String, Address> addressOfHex40, Function<String, String> contractOf) {
        this.network = network;
        this.tokens = tokens;
        this.freezes = freezes;
        this.addressOfHex40 = addressOfHex40;
        this.contractOf = contractOf;
    }

    public void decode(String rawContract, List<String> topics, String data, String txHash, int logIndex, long blockNumber,
                       String blockHash, Instant ts, Decoded out) {
        if (topics.isEmpty() || rawContract == null) return;
        String t0 = topics.getFirst();
        String word = strip0x(data);
        if ((isTransferTopic(t0) || isApprovalTopic(t0)) && topics.size() == 3 && word != null && word.length() == 64) {
            String contract = contractOf.apply(rawContract);
            Token token = tokens.resolve(network, contract);
            Address a = addressOfHex40.apply(topicToAddressHex(topics.get(1)));
            Address b = addressOfHex40.apply(topicToAddressHex(topics.get(2)));
            BigInteger v = new BigInteger(word, 16);
            if (isTransferTopic(t0)) out.transfers.add(new TokenTransfer(network, txHash, logIndex, blockNumber, blockHash, ts, a, b, token, v));
            else out.approvals.add(new TokenApproval(network, txHash, logIndex, blockNumber, blockHash, ts, a, b, token, v));
            return;
        }
        if (decodeAdminChange(rawContract, topics, word, txHash, logIndex, blockNumber, blockHash, ts, out)) return;
        String contract = contractOf.apply(rawContract);
        var source = freezes.find(network, contract);
        if (source.isEmpty()) return;
        var s = source.get();
        TokenFreeze.Action action = sameTopic(s.freezeTopic(), t0) ? TokenFreeze.Action.FREEZE
                : sameTopic(s.unfreezeTopic(), t0) ? TokenFreeze.Action.UNFREEZE : null;
        if (action == null) return;
        String targetHex;
        if (topics.size() == 2) targetHex = topicToAddressHex(topics.get(1));
        else if (topics.size() == 1 && word != null && word.length() == 64 && word.startsWith("0".repeat(24))) targetHex = word.substring(24).toLowerCase();
        else return;
        out.freezes.add(new TokenFreeze(network, txHash, logIndex, blockNumber, blockHash, ts, addressOfHex40.apply(targetHex),
                tokens.resolve(network, contract), s.issuer(), action,
                action == TokenFreeze.Action.FREEZE ? s.freezeEvent() : s.unfreezeEvent()));
    }

    /** Returns true when the log is an admin-change event (decoded or rejected as malformed). */
    private boolean decodeAdminChange(String rawContract, List<String> topics, String word, String txHash, int logIndex,
                                      long blockNumber, String blockHash, Instant ts, Decoded out) {
        String t0 = topics.getFirst();
        String role, event, prevHex = null, newHex;
        if (sameTopic(OWNERSHIP_TRANSFERRED_TOPIC, t0) || sameTopic(ADMIN_CHANGED_TOPIC, t0)) {
            boolean ownership = sameTopic(OWNERSHIP_TRANSFERRED_TOPIC, t0);
            role = ownership ? ContractAdminChange.ROLE_OWNER : ContractAdminChange.ROLE_PROXY_ADMIN;
            event = ownership ? "OwnershipTransferred" : "AdminChanged";
            if (topics.size() == 3 && (word == null || word.isEmpty())) { // both parameters indexed (OpenZeppelin Ownable)
                prevHex = topicToAddressHex(topics.get(1));
                newHex = topicToAddressHex(topics.get(2));
            } else if (topics.size() == 1 && word != null && word.length() == 128 && isAddressWord(word.substring(0, 64))
                    && isAddressWord(word.substring(64))) { // non-indexed (EIP-1967 AdminChanged, Circle Ownable)
                prevHex = word.substring(24, 64).toLowerCase(java.util.Locale.ROOT);
                newHex = word.substring(88).toLowerCase(java.util.Locale.ROOT);
            } else {
                return true;
            }
        } else if (sameTopic(UPGRADED_TOPIC, t0)) {
            if (topics.size() != 2) return true;
            role = ContractAdminChange.ROLE_IMPLEMENTATION;
            event = "Upgraded";
            newHex = topicToAddressHex(topics.get(1));
        } else {
            return false;
        }
        Address contract = new Address(network, contractOf.apply(rawContract));
        out.adminChanges.add(new ContractAdminChange(network, txHash, logIndex, blockNumber, blockHash, ts, contract, role,
                prevHex == null ? null : addressOfHex40.apply(prevHex), addressOfHex40.apply(newHex), event));
        return true;
    }

    private static boolean isAddressWord(String word64) { return word64.startsWith("0".repeat(24)); }
}
