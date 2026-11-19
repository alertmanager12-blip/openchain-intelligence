/**
 * Matched-event fields of real production alerts (read-only extract, 2026-11-19): the 3 newest alerts of each shipped rule
 * present in production, as the API's `matchedEvent` carries them (decimals as strings). Public on-chain data only
 * (addresses, hashes, amounts, tokens, blocks) plus the engine's own scores; watchlist flags removed.
 */
export const ALERT_FIELDS: Record<string, { occurrenceCount: number; fields: Record<string, unknown> }[]> = {
  "fund-splitting": [
    {
      "occurrenceCount": 39,
      "fields": {
        "network": "ethereum",
        "block.hash": "0xa33e7dd30fbd3abd9a9892c7e264aa62b1dc1c7ad7ebeb50b4a8b2eedc027439",
        "block.number": 26406339,
        "transaction.to": "0x52aa899454998be5b000ad077a46bbe360f4e497",
        "wallet.address": "0xbdb3ba9ffe392549e1f8658dd2630c141fdf47b6",
        "block.timestamp": "2026-11-13T13:28:11Z",
        "transaction.from": "0xbdb3ba9ffe392549e1f8658dd2630c141fdf47b6",
        "transaction.hash": "0x5bff610a6b7a742526b960dd5254f8a512e9a7017af2ee9294549917f03e99a9",
        "wallet.risk_level": "MEDIUM",
        "wallet.risk_score": 57,
        "wallet.sanctioned": false,
        "transaction.amount": "1724.617496",
        "counterparty.address": "0x52aa899454998be5b000ad077a46bbe360f4e497",
        "transaction.log_index": 508,
        "transaction.amount_raw": "1724617496",
        "counterparty.risk_score": 57,
        "counterparty.sanctioned": false,
        "transaction.token.known": true,
        "transaction.token.symbol": "USDC",
        "transaction.token.contract": "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
        "transaction.token.decimals": 6
      }
    },
    {
      "occurrenceCount": 4,
      "fields": {
        "network": "ethereum",
        "block.hash": "0x9c2b70117bfe48d735c6f7a3db20717447c54162f39197e3b5e7110ddc49c327",
        "block.number": 26406306,
        "transaction.to": "0x97305c321b0468a508c091b1b8f4b7d0af16b9f1",
        "wallet.address": "0x037741ef196087b26abfb173b64d05df23fadc72",
        "block.timestamp": "2026-11-13T13:21:35Z",
        "transaction.from": "0x037741ef196087b26abfb173b64d05df23fadc72",
        "transaction.hash": "0xa62d5d771e531d361ae09b9432759583156c0af08afbe2690cc84197fca97f71",
        "wallet.risk_level": "LOW",
        "wallet.risk_score": 30,
        "wallet.sanctioned": false,
        "transaction.amount": "211.700000",
        "counterparty.address": "0x97305c321b0468a508c091b1b8f4b7d0af16b9f1",
        "transaction.log_index": 882,
        "transaction.amount_raw": "211700000",
        "counterparty.risk_score": 0,
        "counterparty.sanctioned": false,
        "transaction.token.known": true,
        "transaction.token.symbol": "USDT",
        "transaction.token.contract": "0xdac17f958d2ee523a2206206994597c13d831ec7",
        "transaction.token.decimals": 6
      }
    },
    {
      "occurrenceCount": 6,
      "fields": {
        "network": "ethereum",
        "block.hash": "0x9c2b70117bfe48d735c6f7a3db20717447c54162f39197e3b5e7110ddc49c327",
        "block.number": 26406306,
        "transaction.to": "0xe6f8c49c31334c0f9a1021795496987267e97846",
        "wallet.address": "0x6781b0098e3238caeede00647c9b69dd98a86a83",
        "block.timestamp": "2026-11-13T13:21:35Z",
        "transaction.from": "0x6781b0098e3238caeede00647c9b69dd98a86a83",
        "transaction.hash": "0x5611ca714fe0fb7b0c64a2b58ef9c4d6a64bb747ffe9aa1fa04de82f21090477",
        "wallet.risk_level": "MEDIUM",
        "wallet.risk_score": 37,
        "wallet.sanctioned": false,
        "transaction.amount": "509.400000",
        "counterparty.address": "0xe6f8c49c31334c0f9a1021795496987267e97846",
        "transaction.log_index": 861,
        "transaction.amount_raw": "509400000",
        "counterparty.risk_score": 0,
        "counterparty.sanctioned": false,
        "transaction.token.known": true,
        "transaction.token.symbol": "USDT",
        "transaction.token.contract": "0xdac17f958d2ee523a2206206994597c13d831ec7",
        "transaction.token.decimals": 6
      }
    }
  ],
  "issuer-freeze-counterparty": [
    {
      "occurrenceCount": 1,
      "fields": {
        "network": "ethereum",
        "block.hash": "0xe538a77ddc1e813c2b8f31b3a339cf5cae206936a5019c66d21719c083b2a208",
        "block.number": 26450805,
        "freeze.event": "AddedBlackList",
        "freeze.token": "USDT",
        "token.symbol": "USDT",
        "freeze.action": "FREEZE",
        "freeze.issuer": "tether",
        "wallet.labels": "issuer-frozen",
        "freeze.address": "0x18a739cc448bc3bf746b8f9a90af47adeb81da82",
        "token.contract": "0xdac17f958d2ee523a2206206994597c13d831ec7",
        "wallet.address": "0x18a739cc448bc3bf746b8f9a90af47adeb81da82",
        "block.timestamp": "2026-11-19T18:04:35Z",
        "transaction.hash": "0x58edb779cb8c89ec3a5a5acb17f422b0809954aab78c2e13e86acc4b9cd7b480",
        "wallet.risk_level": "MEDIUM",
        "wallet.risk_score": 61,
        "wallet.sanctioned": false,
        "freeze.token_contract": "0xdac17f958d2ee523a2206206994597c13d831ec7",
        "transaction.log_index": 113,
        "transaction.token.known": true,
        "transaction.token.symbol": "USDT",
        "transaction.token.contract": "0xdac17f958d2ee523a2206206994597c13d831ec7",
        "transaction.token.decimals": 6,
        "freeze.known_counterparties": 1
      }
    },
    {
      "occurrenceCount": 1,
      "fields": {
        "network": "ethereum",
        "block.hash": "0x8f11ac6b3caad5b26067d8aedb73fef92774b30f45f75311a86632ccecabefb1",
        "block.number": 26446237,
        "freeze.event": "AddedBlackList",
        "freeze.token": "USDT",
        "token.symbol": "USDT",
        "freeze.action": "FREEZE",
        "freeze.issuer": "tether",
        "wallet.labels": "issuer-frozen",
        "freeze.address": "0x3ab26e09ab07b22b717aa1791f2fcc0b216c03fc",
        "token.contract": "0xdac17f958d2ee523a2206206994597c13d831ec7",
        "wallet.address": "0x3ab26e09ab07b22b717aa1791f2fcc0b216c03fc",
        "block.timestamp": "2026-11-19T02:47:59Z",
        "transaction.hash": "0xc526aa05df22309cd8a19cabef7c33e0800fa514af907a2831e8c69426aeb2e1",
        "wallet.risk_level": "MEDIUM",
        "wallet.risk_score": 60,
        "wallet.sanctioned": false,
        "freeze.token_contract": "0xdac17f958d2ee523a2206206994597c13d831ec7",
        "transaction.log_index": 5,
        "transaction.token.known": true,
        "transaction.token.symbol": "USDT",
        "transaction.token.contract": "0xdac17f958d2ee523a2206206994597c13d831ec7",
        "transaction.token.decimals": 6,
        "freeze.known_counterparties": 1
      }
    },
    {
      "occurrenceCount": 1,
      "fields": {
        "network": "ethereum",
        "block.hash": "0x8f11ac6b3caad5b26067d8aedb73fef92774b30f45f75311a86632ccecabefb1",
        "block.number": 26446237,
        "freeze.event": "AddedBlackList",
        "freeze.token": "USDT",
        "token.symbol": "USDT",
        "freeze.action": "FREEZE",
        "freeze.issuer": "tether",
        "wallet.labels": "issuer-frozen",
        "freeze.address": "0x893a737ebad19dde1b1569c98c83af324f041488",
        "token.contract": "0xdac17f958d2ee523a2206206994597c13d831ec7",
        "wallet.address": "0x893a737ebad19dde1b1569c98c83af324f041488",
        "block.timestamp": "2026-11-19T02:47:59Z",
        "transaction.hash": "0xfeb12be7feff0263510bccad8ef9b5b7b84b7710066c3295ed54d5f035131c6d",
        "wallet.risk_level": "MEDIUM",
        "wallet.risk_score": 60,
        "wallet.sanctioned": false,
        "freeze.token_contract": "0xdac17f958d2ee523a2206206994597c13d831ec7",
        "transaction.log_index": 8,
        "transaction.token.known": true,
        "transaction.token.symbol": "USDT",
        "transaction.token.contract": "0xdac17f958d2ee523a2206206994597c13d831ec7",
        "transaction.token.decimals": 6,
        "freeze.known_counterparties": 1
      }
    }
  ],
  "large-usdt-transfer": [
    {
      "occurrenceCount": 1,
      "fields": {
        "network": "tron",
        "block.hash": "0000000005410770104ced3772559d2316e7a431a5c52d059db11d283e32d607",
        "block.number": 88147824,
        "transaction.to": "TFxqYTM4L6JrctPEHfiiAREHHcTr8cVYef",
        "wallet.address": "TAYKEnnKvtocjUhpfWVyJm5iPY8vAghpYz",
        "block.timestamp": "2026-11-19T19:45:51Z",
        "transaction.from": "TAYKEnnKvtocjUhpfWVyJm5iPY8vAghpYz",
        "transaction.hash": "4abed781f851bd37c1fcec81a5436dba3a3b10c6b2f8ebeb0a6646d6912e0f51",
        "wallet.risk_level": "LOW",
        "wallet.risk_score": 0,
        "wallet.sanctioned": false,
        "transaction.amount": "15000000.000000",
        "counterparty.address": "TFxqYTM4L6JrctPEHfiiAREHHcTr8cVYef",
        "transaction.log_index": 0,
        "transaction.amount_raw": "15000000000000",
        "counterparty.risk_score": 0,
        "counterparty.sanctioned": false,
        "transaction.token.known": true,
        "transaction.token.symbol": "USDT",
        "transaction.token.contract": "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
        "transaction.token.decimals": 6
      }
    },
    {
      "occurrenceCount": 1,
      "fields": {
        "network": "tron",
        "block.hash": "000000000541038ba28a30366a68b9f86eb3a1ab8a64bac3c1645a44e132ade2",
        "block.number": 88146827,
        "transaction.to": "TAYKEnnKvtocjUhpfWVyJm5iPY8vAghpYz",
        "wallet.address": "TKMHxzMxbVyZ1QTXeyLg8hnJAzmWMSTDNU",
        "block.timestamp": "2026-11-19T18:56:00Z",
        "transaction.from": "TKMHxzMxbVyZ1QTXeyLg8hnJAzmWMSTDNU",
        "transaction.hash": "e34039531484ea9e42f0afdb658916d3c8238cacdbb4bdd1b8abd47c3994bd77",
        "wallet.risk_level": "LOW",
        "wallet.risk_score": 0,
        "wallet.sanctioned": false,
        "transaction.amount": "15000000.000000",
        "counterparty.address": "TAYKEnnKvtocjUhpfWVyJm5iPY8vAghpYz",
        "transaction.log_index": 0,
        "transaction.amount_raw": "15000000000000",
        "counterparty.risk_score": 0,
        "counterparty.sanctioned": false,
        "transaction.token.known": true,
        "transaction.token.symbol": "USDT",
        "transaction.token.contract": "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
        "transaction.token.decimals": 6
      }
    },
    {
      "occurrenceCount": 1,
      "fields": {
        "network": "tron",
        "block.hash": "0000000005410371e4416168c851e6091c53eedba395ce45afaead41d442e5e6",
        "block.number": 88146801,
        "transaction.to": "TYE5mNXSQHuPAdCa1etk76BKybD4FKJto6",
        "wallet.address": "TLaGjwhvA8XQYSxFAcAXy7Dvuue9eGYitv",
        "block.timestamp": "2026-11-19T18:54:42Z",
        "transaction.from": "TLaGjwhvA8XQYSxFAcAXy7Dvuue9eGYitv",
        "transaction.hash": "6427b06b66d014b5e33ef583d6d5b6146a6f665e8f8a19b81c6435c9a503a8b9",
        "wallet.risk_level": "HIGH",
        "wallet.risk_score": 70,
        "wallet.sanctioned": false,
        "transaction.amount": "12672425.550000",
        "counterparty.address": "TYE5mNXSQHuPAdCa1etk76BKybD4FKJto6",
        "transaction.log_index": 0,
        "transaction.amount_raw": "12672425550000",
        "counterparty.risk_score": 32,
        "counterparty.sanctioned": false,
        "transaction.token.known": true,
        "transaction.token.symbol": "USDT",
        "transaction.token.contract": "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
        "transaction.token.decimals": 6
      }
    }
  ],
  "listed-address-exposure": [
    {
      "occurrenceCount": 1,
      "fields": {
        "signal.type": "INDIRECT_EXPOSURE",
        "signal.title": "Indirect exposure to a listed address",
        "signal.summary": "Received funds that trace back, through 1 intermediary address, to an address listed as frozen by the stablecoin issuer by tether-onchain (at most 29,316.25 USDT traced).",
        "wallet.address": "0x6487986a78b126538746937fbd25516971129b3a",
        "signal.severity": "MEDIUM",
        "transaction.hash": "0x45378059ca1f48202455a69341e030973a9158b3d8e41e735be5250c9090476a",
        "signal.confidence": "0.5",
        "signal.evidence_level": "RISK_SIGNAL",
        "signal.listed_category": "issuer-frozen"
      }
    },
    {
      "occurrenceCount": 1,
      "fields": {
        "signal.type": "INDIRECT_EXPOSURE",
        "signal.title": "Indirect exposure to a listed address",
        "signal.summary": "Received funds that trace back, through 1 intermediary address, to an address listed as frozen by the stablecoin issuer by tether-onchain (at most 98.32 USDT traced).",
        "wallet.address": "0x0eb8d76a3895938870f70aa77a58214f27ef9aa9",
        "signal.severity": "MEDIUM",
        "transaction.hash": "0x3fa24d2c70e56288b372f5d4ea067de24b3e49205575abebd78514b7c222cfb4",
        "signal.confidence": "0.5",
        "signal.evidence_level": "RISK_SIGNAL",
        "signal.listed_category": "issuer-frozen"
      }
    },
    {
      "occurrenceCount": 1,
      "fields": {
        "signal.type": "INDIRECT_EXPOSURE",
        "signal.title": "Indirect exposure to a listed address",
        "signal.summary": "Received funds that trace back, through 1 intermediary address, to an address listed as frozen by the stablecoin issuer by tether-onchain (at most 14.96 USDT traced).",
        "wallet.address": "0xa243d7d2e2e7984511331dbbf2cf3acfe7dccc96",
        "signal.severity": "MEDIUM",
        "transaction.hash": "0xef3b6b970a56f3f2c47c7c4a6802d39af94bd858b9de1c5de25f1c81a840c085",
        "signal.confidence": "0.5",
        "signal.evidence_level": "RISK_SIGNAL",
        "signal.listed_category": "issuer-frozen"
      }
    }
  ],
  "sanctioned-exposure-signal": [
    {
      "occurrenceCount": 2,
      "fields": {
        "signal.type": "INDIRECT_EXPOSURE",
        "signal.title": "Indirect exposure to a listed address",
        "signal.summary": "Received funds that trace back, through 1 intermediary address, to an address listed as frozen by the stablecoin issuer by tether-onchain (at most 0.011 ETH traced).",
        "wallet.address": "0x2c32f297fb37b299a7ac29ef14894d8bd4d840d1",
        "signal.severity": "MEDIUM",
        "transaction.hash": "0xeb59c9532c2e1028ce6e2aebbf2f550d85db83ac5caf7dda9ca67c0b4eb1d9c4",
        "signal.confidence": "0.5",
        "signal.evidence_level": "RISK_SIGNAL"
      }
    },
    {
      "occurrenceCount": 1,
      "fields": {
        "signal.type": "INDIRECT_EXPOSURE",
        "signal.title": "Indirect exposure to a listed address",
        "signal.summary": "Received funds that trace back, through 1 intermediary address, to an address listed as frozen by the stablecoin issuer by tether-onchain (at most 0.011 ETH traced).",
        "wallet.address": "0xe77037f41aac608d927753b468d90eb4ac17863a",
        "signal.severity": "MEDIUM",
        "transaction.hash": "0x3d2bbf8195b69f897c69adbd64ec94e9f630935e2132e3290d5b598975fef6fc",
        "signal.confidence": "0.5",
        "signal.evidence_level": "RISK_SIGNAL"
      }
    },
    {
      "occurrenceCount": 1,
      "fields": {
        "signal.type": "INDIRECT_EXPOSURE",
        "signal.title": "Indirect exposure to a listed address",
        "signal.summary": "Received funds that trace back, through 1 intermediary address, to an address listed as frozen by the stablecoin issuer by tether-onchain (at most 975 USDT traced).",
        "wallet.address": "0xb69a051e6eb38f8e2b1d9f2bda5e1879f6e970e2",
        "signal.severity": "MEDIUM",
        "transaction.hash": "0x2712592fb36e0b22284a827e0180aa317115a255f421d0853cf4d9274248c0df",
        "signal.confidence": "0.5",
        "signal.evidence_level": "RISK_SIGNAL"
      }
    }
  ],
  "wallet-draining": [
    {
      "occurrenceCount": 1,
      "fields": {
        "network": "ethereum",
        "block.hash": "0x6b79945071207db701846c0858f30c5344ade7b190431b94202f56bf5b218dfe",
        "block.number": 26451589,
        "transaction.to": "0x881d40237659c251811cec9c364ef91dc08d300c",
        "wallet.address": "0x5f8939edcb4413227fa9f8e1337befdb42270fce",
        "wallet.balance": "1.676962146831766324",
        "block.timestamp": "2026-11-19T20:41:23Z",
        "transaction.fee": "0.001894704830289600",
        "transaction.from": "0x5f8939edcb4413227fa9f8e1337befdb42270fce",
        "transaction.hash": "0xd405623019f3519d93511352c33cd1f3ece0e48d587f47fab8aa48a02ff278d0",
        "wallet.risk_level": "LOW",
        "wallet.risk_score": 15,
        "wallet.sanctioned": false,
        "transaction.amount": "1.672907364653609768",
        "transaction.method": "0x5f575529",
        "transaction.success": true,
        "counterparty.address": "0x881d40237659c251811cec9c364ef91dc08d300c",
        "transaction.amount_raw": "1672907364653609768",
        "counterparty.risk_score": 10,
        "counterparty.sanctioned": false,
        "transaction.token.known": true,
        "transaction.token.symbol": "ETH",
        "transaction.token.decimals": 18,
        "transaction.contract_interaction": true
      }
    },
    {
      "occurrenceCount": 1,
      "fields": {
        "network": "ethereum",
        "block.hash": "0x75c2aba070d4d2bf29dbe623fde9595dfab3a95069e5796241bd3b10c5006a5b",
        "block.number": 26451580,
        "transaction.to": "0xf30ba13e4b04ce5dc4d254ae5fa95477800f0eb0",
        "wallet.address": "0x3cb16a661f72cd06fbd4f98a915b46c4eba0daec",
        "wallet.balance": "1.323585575491725431",
        "block.timestamp": "2026-11-19T20:39:35Z",
        "transaction.fee": "0.000052586055417000",
        "transaction.from": "0x3cb16a661f72cd06fbd4f98a915b46c4eba0daec",
        "transaction.hash": "0xf344ff81c0288b7375f38998eacefb2152314d209a94b81af630a06728e3c0d2",
        "wallet.risk_level": "MEDIUM",
        "wallet.risk_score": 37,
        "wallet.sanctioned": false,
        "transaction.amount": "1.073804041567966094",
        "transaction.success": true,
        "counterparty.address": "0xf30ba13e4b04ce5dc4d254ae5fa95477800f0eb0",
        "transaction.amount_raw": "1073804041567966094",
        "counterparty.risk_score": 62,
        "counterparty.sanctioned": false,
        "transaction.token.known": true,
        "transaction.token.symbol": "ETH",
        "transaction.token.decimals": 18,
        "transaction.contract_interaction": false
      }
    },
    {
      "occurrenceCount": 1,
      "fields": {
        "network": "ethereum",
        "block.hash": "0x75c2aba070d4d2bf29dbe623fde9595dfab3a95069e5796241bd3b10c5006a5b",
        "block.number": 26451580,
        "transaction.to": "0xffc65b3bef0544b2dbfa40deea02c61f60397958",
        "wallet.address": "0xbba25cc66b8a21364ab589e58cc15de9b7b826af",
        "wallet.balance": "30.099862720305955120",
        "block.timestamp": "2026-11-19T20:39:35Z",
        "transaction.fee": "0.001592042827749675",
        "transaction.from": "0xbba25cc66b8a21364ab589e58cc15de9b7b826af",
        "transaction.hash": "0x0d8dec082bb1ad1d8d5e3626efe784673ff0564582c0bf10281acf718ee66a75",
        "wallet.risk_level": "LOW",
        "wallet.risk_score": 30,
        "wallet.sanctioned": false,
        "transaction.amount": "30.000000000000000000",
        "transaction.method": "0xac9650d8",
        "transaction.success": true,
        "counterparty.address": "0xffc65b3bef0544b2dbfa40deea02c61f60397958",
        "transaction.amount_raw": "30000000000000000000",
        "counterparty.risk_score": 8,
        "counterparty.sanctioned": false,
        "transaction.token.known": true,
        "transaction.token.symbol": "ETH",
        "transaction.token.decimals": 18,
        "transaction.contract_interaction": true
      }
    }
  ]
};
