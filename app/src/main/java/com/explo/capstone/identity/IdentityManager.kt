package com.explo.capstone.identity

import android.content.Context
import com.explo.capstone.shared.EncryptedDocument
import com.explo.capstone.shared.User

/**
 * Owner: Sandrani Balachandu
 * Responsible for: Hardware-bound user identity via Android Keystore and encrypted document vault.
 *
 * TODO Sandrani:
 *  1. Implement provisionIdentity() — generate a hardware-backed key pair in Keystore
 *  2. Implement getUserIdentity() — retrieve the stored User object
 *  3. Implement storeDocument() — AES-256 encrypt and store a file locally
 *  4. Implement retrieveDocument() — decrypt and return a stored document
 */
class IdentityManager(private val context: Context) {

    /**
     * First-time setup: create a hardware-backed key pair and persist a User record.
     * @param displayName the user's chosen name
     * @return the provisioned User object
     */
    fun provisionIdentity(displayName: String): User {
        // TODO: use Android Keystore KeyPairGenerator with StrongBoxBacked = true
        throw NotImplementedError("Sandrani: implement provisionIdentity")
    }

    /**
     * Retrieve the current device's User identity.
     */
    fun getUserIdentity(): User? {
        // TODO: load from EncryptedSharedPreferences
        throw NotImplementedError("Sandrani: implement getUserIdentity")
    }

    /**
     * Store a document in the local encrypted vault.
     */
    fun storeDocument(missionId: String, fileName: String, fileBytes: ByteArray): EncryptedDocument {
        // TODO: AES-256 encrypt fileBytes, save to internal storage
        throw NotImplementedError("Sandrani: implement storeDocument")
    }

    /**
     * Retrieve and decrypt a document from the vault.
     */
    fun retrieveDocument(documentId: String): ByteArray {
        // TODO: load encrypted file, decrypt with AES-256
        throw NotImplementedError("Sandrani: implement retrieveDocument")
    }
}
