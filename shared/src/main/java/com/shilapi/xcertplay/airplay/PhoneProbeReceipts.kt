package com.shilapi.xcertplay.airplay

/** Bounded diagnostic correlation only; even status 200 is not a navigation-executed receipt. */
internal class PhoneProbeReceipts {
    private val requests = LinkedHashMap<Int, String>()
    @Synchronized fun sent(cseq: Int, id: String) {
        requests[cseq] = id
        while (requests.size > 8) requests.remove(requests.keys.first())
    }
    @Synchronized fun take(cseq: Int): String? = requests.remove(cseq)
}
