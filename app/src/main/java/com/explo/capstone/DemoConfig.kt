package com.explo.capstone

/**
 * Toggle BYPASS_SIGNAL to true to skip Signal encryption during demos.
 * Provisioning still runs; messages are sent/received as raw plaintext (MessageType.PLAIN_TEXT).
 * Flip this one constant and rebuild — no other changes needed.
 */
object DemoConfig {
    const val BYPASS_SIGNAL = true
}
