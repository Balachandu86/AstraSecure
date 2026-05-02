# End-to-End Message Flow

Two devices: **Alice** (sender) and **Bob** (recipient). Both have the app installed, provisioned, and connected to the same server. They are in the same AstraSecure channel.

---

## Stage 1 — Provisioning (one-time, each device)

```
Alice's device                        Server (PostgreSQL)
─────────────────                     ──────────────────
IdentityManager.provisionIdentity()
  → generateKeystoreKeyPair()          // secp256r1 in Keystore (device attestation)
  → SignalKeyManager.provision()
      → KeyHelper.generateIdentityKeyPair()   // Curve25519 (Signal identity)
      → KeyHelper.generateRegistrationId()
      → KeyHelper.generateSignedPreKey()      // SPK_A, signed with IK_A
      → KeyHelper.generatePreKeys(100)        // OPK_A[1..100]
      → store all private halves in AstraSignalProtocolStore
      → POST /v1/users {
            userId, displayName, registrationId,
            identityKey: IK_A_pub,
            signedPreKey: { id, SPK_A_pub, SPK_A_sig },
            oneTimePreKeys: [{ id, OPK_A_pub[1] }, ..., { id, OPK_A_pub[100] }]
        }
                                        → INSERT INTO users
                                        → INSERT INTO signed_prekeys
                                        → INSERT INTO one_time_prekeys (×100)
                                        ← 201 { token: jwt_A }
  ← JWT stored in EncryptedSharedPreferences

// Same flow runs on Bob's device → server holds IK_B, SPK_B, OPK_B[1..100]
```

---

## Stage 2 — Channel join and Sender Key distribution

Alice creates (or joins) a channel. The channel already has Bob as a member.

```
Alice's device                        Server                    Bob's device
─────────────────                     ──────────────────        ────────────
POST /v1/channels/{channelId}/members
                                      → INSERT INTO channel_members

// Alice generates her sender key for this channel
SignalCryptoEngine.distributeChannelSenderKey(channelId, alice.id, [bob.id])

  // Alice needs a session with Bob to deliver the SKDM
  → store.containsSession(bobAddress) == false
  → GET /v1/keys/bob-uuid
                                      // Atomically pop one OPK for Bob
                                      DELETE FROM one_time_prekeys
                                      WHERE user_id='bob' LIMIT 1
                                      RETURNING opk_id, opk_public
                                      ← { IK_B_pub, SPK_B_pub, SPK_B_sig, OPK_B_pub[1] }

  // Alice runs X3DH
  EK_A = Curve25519.generateKeyPair()
  DH1 = DH(IK_A_priv,  SPK_B_pub)
  DH2 = DH(EK_A_priv,  IK_B_pub)
  DH3 = DH(EK_A_priv,  SPK_B_pub)
  DH4 = DH(EK_A_priv,  OPK_B_pub[1])
  SK  = HKDF(DH1||DH2||DH3||DH4)
  SessionBuilder(store, bobAddress).process(bundle)  // stores session state

  // Create Alice's sender key for the channel
  skdm = SenderKeyGroupCipher(store, "channelId:alice").create()
  // skdm contains Alice's sender chain key and signing key public component

  // Encrypt the SKDM to Bob via 1:1 session
  ciphertext = SessionCipher(store, bobAddress).encrypt(skdm.serialize())
  // This produces a PreKeySignalMessage (first message to Bob ever)

  POST /v1/messages/bob-uuid {
      senderId: alice-uuid,
      channelId: channelId,
      messageType: 3,   // SenderKeyDistributionMessage
      ciphertext: base64(PreKeySignalMessage wrapping SKDM)
  }
                                      → INSERT INTO message_queue
                                      → push via WebSocket if Bob connected
                                                                WebSocket push arrives
                                                                GET /v1/messages (or WS)
                                                                ← message { type=3 }

                                                                // Bob decrypts outer session
                                                                SessionCipher(store, aliceAddress)
                                                                  .decrypt(PreKeySignalMessage)
                                                                // Bob runs X3DH from his side:
                                                                DH1 = DH(SPK_B_priv, IK_A_pub)
                                                                DH2 = DH(IK_B_priv,  EK_A_pub)
                                                                DH3 = DH(SPK_B_priv, EK_A_pub)
                                                                DH4 = DH(OPK_B_priv[1], EK_A_pub)
                                                                SK  = HKDF(DH1||DH2||DH3||DH4)
                                                                // SK matches Alice's SK ✓
                                                                // Session established

                                                                // Bob decrypts SKDM
                                                                SenderKeyGroupCipher.process(skdm)
                                                                // Bob now holds Alice's channel sender key
                                                                DELETE /v1/messages { ids: [queueId] }
```

---

## Stage 3 — Alice sends a channel message

```
Alice's device                        Server                    Bob's device
─────────────────                     ──────────────────        ────────────
User taps SEND "EXTRACTION CONFIRMED AT 0300"

ChatViewModel.handle(ChatIntent.Send("EXTRACTION CONFIRMED AT 0300"))
  → messageRepository.send(channelId, missionKeyAlias, alice.id, plaintext, categoryId)

  // Signal channel encrypt (single encryption for all channel members)
  ciphertext = SenderKeyGroupCipher(store, "channelId:alice").encrypt(plaintext)
  // Ratchets Alice's sender chain: message_key[n] derived, chain_key advanced

  // Metadata obfuscation (unchanged from current impl)
  padded = metadataProcessor.padMessage(ciphertext)     // pad to 256-byte boundary
  delay(metadataProcessor.randomizedDelayMs(200, 2000)) // timing jitter

  // Store locally (outgoing bubble appears immediately in UI)
  store.updateMessages { it + Message(encryptedContent = padded) }

  // Relay to server for Bob
  POST /v1/messages/bob-uuid {
      senderId: alice-uuid,
      channelId: channelId,
      messageType: 2,   // SignalMessage (regular, session established)
      ciphertext: base64(padded)
  }
                                      → INSERT INTO message_queue
                                      → WebSocket push to Bob
                                                                WebSocket: message arrives

                                                                // Bob decrypts
                                                                plaintext = SenderKeyGroupCipher(
                                                                    store, "channelId:alice"
                                                                ).decrypt(ciphertext)
                                                                // message_key[n] derived from
                                                                // Alice's sender chain state
                                                                // Bob can decrypt out-of-order
                                                                // (Signal handles skipped messages)

                                                                // Display in chat
                                                                store.updateMessages { it + Message(...) }

                                                                // Acknowledge delivery
                                                                DELETE /v1/messages { ids: [queueId] }
```

---

## Stage 4 — Bob replies (DH ratchet advances)

```
Bob's device                          Server                    Alice's device
────────────                          ──────────────────        ─────────────────
// Bob replies using his own sender key for the channel
// (Bob must also have distributed his SKDM to Alice — same Stage 2 flow, reversed)

ciphertext = SenderKeyGroupCipher(store, "channelId:bob").encrypt(replyBytes)

POST /v1/messages/alice-uuid { messageType: 2, ciphertext }
                                      → queue
                                      → WebSocket push
                                                                Alice decrypts via
                                                                SenderKeyGroupCipher(store, "channelId:bob")
                                                                .decrypt(ciphertext)
```

Each sender has their own ratcheting chain per channel. Bob's chain advances independently of Alice's.

---

## Stage 5 — Key rotation event (existing UI, new backend)

The existing key rotation timer in ChatScreen calls `cryptoEngine.rotateMissionKey()`. With Signal, this maps to rotating Alice's sender key for the channel — she generates a new `SenderKeyDistributionMessage` and re-distributes it to all members, just like Stage 2. This evicts any passive eavesdropper who may have obtained an old sender key.

```
Alice's device
─────────────────
User taps ROTATE KEY

ChatViewModel.handle(ChatIntent.RotateKey)
  → signalCryptoEngine.distributeChannelSenderKey(
        channelId, alice.id, channelMembers
    )
  // Generates new sender chain, distributes SKDM to all members
  // Old chain key is deleted from store
  // Future messages from Alice use new chain
```

---

## Error states and fallbacks

| Scenario | What happens |
|---|---|
| Server unreachable on send | `transport.sendMessage()` returns `Result.failure`; VM shows `SEND_FAILED // RETRY` (already in existing UI) |
| OPK exhausted on server (no OPK in bundle) | X3DH proceeds without DH4; still secure, slightly weaker (no OPK). libsignal handles this automatically |
| Bob's session reset (reinstall) | Bob sends Alice a new `PreKeySignalMessage`; Alice's `SessionCipher.decrypt()` automatically re-establishes session from the new X3DH material |
| Ciphertext tampered in transit | libsignal throws `InvalidMessageException`; existing UI renders `[ CIPHERTEXT // INTEGRITY_FAIL ]` |
| Sender key not yet received (SKDM in flight) | `SenderKeyGroupCipher.decrypt()` throws; message renders as `[ CIPHERTEXT // INTEGRITY_FAIL ]` until SKDM arrives and is processed; libsignal queues the ciphertext for retry |
| Panic wipe | `signalStore.wipeAll()` deletes all sessions, pre-keys, sender keys from EncryptedSharedPreferences. Server-side: `revokeRemoteTokens()` (currently no-op) should call `DELETE /v1/users/{userId}` to remove the user's pre-key records from the server |
