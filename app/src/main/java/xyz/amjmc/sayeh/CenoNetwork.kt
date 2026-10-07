package xyz.amjmc.sayeh

/**
 * Public Ouinet network parameters of the Ceno network, copied verbatim from
 * censorship-no/ceno-browser local.properties.sample (published by eQualitie).
 */
object CenoNetwork {
    const val CACHE_PUB_KEY = "c9fd85cfc331e9e958e749349f7902625ae6d7bb9ca77bd53e00a9ee485ed15b"
    const val INJECTOR_CREDENTIALS = "ouinet:160d79874a52c2cbcdec58db1a8160a9"
    const val INJECTOR_TLS_CERT =
        "-----BEGIN CERTIFICATE-----\n" +
        "MIICyTCCAbGgAwIBAgIGAWwvE3jIMA0GCSqGSIb3DQEBCwUAMBQxEjAQBgNVBAMMCWxvY2FsaG9zdDAeFw0xOTA3MjQxNjE4MjFaFw0zNDA3MjIxNjE4MjFaMBQxEjAQBgNVBAMMCWxvY2FsaG9zdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAOQ6tX1fh1JQJGMEpgEaqFdVpl2Jz39s+3pFJAHRQMxvQa1a4pGwlc4smrhh8Y2ZKli8zhIzFPATZ3ipdBwnLBBUnDqpZWEqsKdBGGJghM+8EitXJwtSWjR2qqZcz3Xz60MKt2S2IeL6L3/HtHM1bN93Xo3hQK/WYDQ6BEeLd6JSsns1mwwccTStu/kc3Y2EIXPh1otQ624QXb9szIdwQw7vzi0saXONdaFFbpRyoa6KKCEC7iHHfUbEhCSRpL8YMrl5z9mKqA8y+5tl3jzTHRtYE4SVG60pmd9nMQ33ue8m5ADq5Bd8Jg2qOmmg0KNFV1RHB3pljMGco6eP9zmb3jsCAwEAAaMhMB8wHQYDVR0OBBYEFMCGT2KEmo4kM08CE/rv/BbnmVfnMA0GCSqGSIb3DQEBCwUAA4IBAQBWxR7x1vADpkpVRzNxicLgd0CYitmhEWtRQp9kE33O5BjRlHQ5TTA0WBp8Nc3c5ZZ1qAnQx3tXVZ7W1QY2XjiQpsPEhFcPsAtFLP+kpDEFPi39iFv4gunR4M1zReCDTGTJ48bLtqONZ9XgJ7obW8r+TjuJyI/i11NWUwKldg0NevF1Bkddbhpt7PJHUpSSbwr3GJOKHfRw9ZaX6P86MVcJd0TaAzZPXqk+2eab43GbbD6keXRGIufMThKGyrRX+9aIaV3tx3uWAOfWVmlzf9w3gV3DlmjPSOXmUsOLk0PFwoy7O7n9zJKNrUy1N2O+j0tH5HVXOnSjpS8aNrMtpfHS\n" +
        "-----END CERTIFICATE-----\n" +
        ""
    const val CACHE_TYPE = "bep5-http"

    /**
     * Extra BitTorrent DHT bootstrap nodes, all four from BuildConfig.BT_BOOTSTRAP_EXTRAS of the
     * official Ceno 2.11.7 release APK (extracted by .github/workflows/extract-bootstrap.yml).
     * The engine tries these in parallel with its 5 built-in nodes and the contacts it saved from
     * earlier runs; any single one answering is enough, a blocked one just times out.
     * IR_1 is proven on our line; the others are spares in case it gets blocked.
     */
    const val BT_BOOTSTRAP_IR_1 = "213.176.120.180"
    val BT_BOOTSTRAP_EXTRAS = setOf(
        BT_BOOTSTRAP_IR_1,   // IR_1
        "185.126.239.184",   // RU_1
        "89.169.169.37",     // RU_2
        "147.78.3.239",      // UA_1
    )
    const val CA_STORE_ASSET = "file:///android_asset/cacert.pem"
}
